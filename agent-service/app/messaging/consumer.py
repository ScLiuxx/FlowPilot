"""aio-pika consumer of the agent command queues.

Two durable queues are consumed:

* ``agent.execute.queue``  — bound to ``agent.execute``
* ``agent.control.queue`` — bound to ``agent.resume`` and ``agent.cancel``

Both queues dead letter to ``flowpilot.dead-letter.queue``.

Delivery rules:

* messages are **persistent** and acknowledged **manually**,
* the ACK is only sent after the business work succeeded,
* the result event is *not* published by the consumer: it is written to the
  transactional outbox together with the execution record and delivered by the
  outbox publisher, therefore the ACK never loses a result,
* a transient failure is requeued with a bounded attempt counter, an
  unrecoverable error is rejected into the dead letter queue (never an
  infinite requeue loop),
* the connection is established with ``connect_robust`` so that the consumer
  survives a broker restart.
"""

from __future__ import annotations

import json
from typing import Any

import aio_pika
from aio_pika import ExchangeType

from app.config.logging import get_logger, log_context
from app.config.settings import Settings, get_settings
from app.messaging.events import (
    AgentCancelPayload,
    AgentExecutePayload,
    AgentResumePayload,
    MessageEnvelope,
    MessageType,
    ProtocolSchemaRegistry,
    ProtocolViolation,
    parse_envelope,
)
from app.messaging.producer import EventPublisher
from app.runtime.executor import AgentExecutor, ExecutionOutcome

logger = get_logger(__name__)

#: Major protocol version this consumer understands.
SUPPORTED_SCHEMA_MAJOR = "1"


class ProtocolError(Exception):
    """The message does not conform to the protocol — dead letter it."""


class AgentMessageConsumer:
    """Consumes ``agent.execute`` / ``agent.resume`` / ``agent.cancel``."""

    def __init__(
        self,
        *,
        executor: AgentExecutor,
        publisher: EventPublisher,
        settings: Settings | None = None,
        schema_registry: ProtocolSchemaRegistry | None = None,
    ) -> None:
        self._executor = executor
        self._publisher = publisher
        self._settings = settings or get_settings()
        self._schema_registry = schema_registry or ProtocolSchemaRegistry(
            self._settings.protocol_schema_dir
        )
        self._connection: Any = None
        self._channel: Any = None
        self._queues: list[Any] = []
        self._attempts: dict[str, int] = {}
        self._started = False

    @property
    def connected(self) -> bool:
        return self._connection is not None

    # -- lifecycle ---------------------------------------------------------
    async def start(self) -> None:
        """Connect, declare the topology and start consuming."""
        await self._publisher.connect()
        self._connection = await aio_pika.connect_robust(self._settings.rabbitmq_url)
        self._channel = await self._connection.channel()
        await self._channel.set_qos(prefetch_count=self._settings.rabbitmq_prefetch)

        exchange = await self._channel.declare_exchange(
            self._settings.rabbitmq_exchange, ExchangeType.TOPIC, durable=True
        )
        dead_letter_exchange = await self._channel.declare_exchange(
            self._settings.rabbitmq_dead_letter_exchange, ExchangeType.TOPIC, durable=True
        )
        dead_letter_queue = await self._channel.declare_queue(
            self._settings.rabbitmq_dead_letter_queue, durable=True
        )
        await dead_letter_queue.bind(
            dead_letter_exchange, routing_key=self._settings.rabbitmq_dead_letter_routing_key
        )

        queue_arguments = {
            "x-dead-letter-exchange": self._settings.rabbitmq_dead_letter_exchange,
            "x-dead-letter-routing-key": self._settings.rabbitmq_dead_letter_routing_key,
        }

        execute_queue = await self._channel.declare_queue(
            self._settings.rabbitmq_execute_queue, durable=True, arguments=queue_arguments
        )
        await execute_queue.bind(exchange, routing_key="agent.execute")

        control_queue = await self._channel.declare_queue(
            self._settings.rabbitmq_control_queue, durable=True, arguments=queue_arguments
        )
        await control_queue.bind(exchange, routing_key="agent.resume")
        await control_queue.bind(exchange, routing_key="agent.cancel")

        self._queues = [execute_queue, control_queue]
        for queue in self._queues:
            await queue.consume(self._on_message, no_ack=False)
        self._started = True
        logger.info(
            "agent consumer started",
            extra={
                "queues": [queue.name for queue in self._queues],
                "exchange": self._settings.rabbitmq_exchange,
            },
        )

    async def stop(self) -> None:
        self._started = False
        for queue in self._queues:
            try:
                await queue.cancel()
            except Exception:  # pragma: no cover - best effort shutdown
                logger.exception("failed to cancel a queue consumer")
        self._queues = []
        if self._connection is not None:
            await self._connection.close()
            self._connection = None
            self._channel = None

    # -- message handling ---------------------------------------------------
    async def _on_message(self, message: Any) -> None:
        await self.handle(message)

    async def handle(self, message: Any) -> None:
        """Handle one incoming message (manual ack, bounded requeue)."""
        message_id = getattr(message, "message_id", None) or id(message)
        try:
            envelope = self._parse(message)
        except ProtocolError as exc:
            logger.warning(
                "rejecting invalid message",
                extra={"error": str(exc), "messageId": message_id},
            )
            await self._reject(message)
            return

        with log_context(
            trace_id=envelope.traceId,
            invocation_id=envelope.invocationId,
            thread_id=(envelope.payload or {}).get("threadId"),
            message_id=envelope.messageId,
        ):
            try:
                await self._dispatch(envelope)
                # The result event was written to the transactional outbox
                # together with the execution record: the ACK therefore never
                # loses a result.
                await message.ack()
                self._attempts.pop(message_id, None)
            except Exception as exc:
                await self._handle_failure(message, message_id, exc)

    def _parse(self, message: Any) -> MessageEnvelope:
        body = getattr(message, "body", None)
        if body is None:
            raise ProtocolError("message has no body")
        try:
            envelope = parse_envelope(body)
        except Exception as exc:
            raise ProtocolError(f"invalid envelope: {exc}") from exc

        major = str(envelope.schemaVersion).split(".")[0]
        if major != SUPPORTED_SCHEMA_MAJOR:
            raise ProtocolError(
                f"unsupported protocol version {envelope.schemaVersion}"
            )
        if self._schema_registry.available:
            try:
                self._schema_registry.validate_message(envelope)
            except ProtocolViolation as exc:
                raise ProtocolError(f"protocol violation: {exc}") from exc
        return envelope

    async def _dispatch(self, envelope: MessageEnvelope) -> ExecutionOutcome | None:
        payload = envelope.payload or {}
        if envelope.messageType == MessageType.AGENT_EXECUTE:
            execute_payload = AgentExecutePayload.model_validate(payload)
            return await self._executor.execute(envelope, execute_payload)
        if envelope.messageType == MessageType.AGENT_RESUME:
            resume_payload = AgentResumePayload.model_validate(payload)
            return await self._executor.resume(envelope, resume_payload)
        if envelope.messageType == MessageType.AGENT_CANCEL:
            cancel_payload = AgentCancelPayload.model_validate(payload)
            return await self._executor.cancel(envelope, cancel_payload)
        raise ProtocolError(
            f"message type {envelope.messageType.value} is not handled by this consumer"
        )

    async def _handle_failure(self, message: Any, message_id: str, exc: Exception) -> None:
        """Requeue transient failures (bounded), dead letter everything else."""
        attempts = self._attempts.get(message_id, 0) + 1
        self._attempts[message_id] = attempts
        retryable = isinstance(exc, (aio_pika.AMQPException, ConnectionError, TimeoutError, OSError))
        logger.warning(
            "message handling failed",
            extra={
                "error": str(exc),
                "errorType": type(exc).__name__,
                "attempt": attempts,
                "retryable": retryable,
            },
        )
        if retryable and attempts <= self._settings.rabbitmq_max_redeliveries:
            try:
                await message.nack(requeue=True)
            except Exception:  # pragma: no cover - broker gone
                logger.exception("failed to nack the message")
            return
        await self._reject(message)

    async def _reject(self, message: Any) -> None:
        """Reject without requeue -> the message lands in the dead letter queue."""
        try:
            await message.reject(requeue=False)
        except Exception:  # pragma: no cover - broker gone
            logger.exception("failed to reject the message")


def build_message_body(envelope: MessageEnvelope) -> bytes:
    """Serialise an envelope for the wire (used by the tests and the demo)."""
    return json.dumps(envelope.model_dump(mode="json"), ensure_ascii=False).encode("utf-8")
