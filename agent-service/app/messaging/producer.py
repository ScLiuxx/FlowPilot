"""Publishing of the agent result events.

The producer uses publisher confirms: a message is only considered published
when RabbitMQ confirmed it. All messages are persistent and are published to
the durable topic exchange ``flowpilot.events``.
"""

from __future__ import annotations

import json
from typing import Any

import aio_pika
from aio_pika import DeliveryMode, ExchangeType, Message

from app.config.logging import get_logger
from app.config.settings import Settings, get_settings
from app.messaging.events import (
    ROUTING_BY_MESSAGE_TYPE,
    MessageEnvelope,
    MessageType,
    ProtocolSchemaRegistry,
    build_outcome_envelope,
    envelope_to_dict,
)

logger = get_logger(__name__)


class EventPublisher:
    """Publishes agent events with publisher confirms."""

    def __init__(
        self,
        *,
        settings: Settings | None = None,
        exchange: Any = None,
        schema_registry: ProtocolSchemaRegistry | None = None,
    ) -> None:
        self._settings = settings or get_settings()
        self._exchange = exchange
        self._connection: Any = None
        self._schema_registry = schema_registry or ProtocolSchemaRegistry(
            self._settings.protocol_schema_dir
        )

    @property
    def connected(self) -> bool:
        return self._exchange is not None

    async def connect(self) -> None:
        """Open the connection and declare the exchange."""
        if self._exchange is not None:
            return
        self._connection = await aio_pika.connect_robust(self._settings.rabbitmq_url)
        channel = await self._connection.channel(publisher_confirms=True)
        await channel.set_qos(prefetch_count=self._settings.rabbitmq_prefetch)
        self._exchange = await channel.declare_exchange(
            self._settings.rabbitmq_exchange,
            ExchangeType.TOPIC,
            durable=True,
        )
        logger.info("connected to rabbitmq", extra={"exchange": self._settings.rabbitmq_exchange})

    async def close(self) -> None:
        if self._connection is not None:
            await self._connection.close()
            self._connection = None
            self._exchange = None

    async def publish(self, envelope: MessageEnvelope, routing_key: str) -> MessageEnvelope:
        """Publish a message and wait for the publisher confirm."""
        if self._exchange is None:
            raise RuntimeError("publisher is not connected")
        body = json.dumps(envelope_to_dict(envelope), ensure_ascii=False).encode("utf-8")
        message = Message(
            body=body,
            delivery_mode=DeliveryMode.PERSISTENT,
            content_type="application/json",
            message_id=envelope.messageId,
            correlation_id=envelope.correlationId,
            timestamp=envelope.timestamp,
            headers={
                "messageType": envelope.messageType.value,
                "schemaVersion": envelope.schemaVersion,
                "traceId": envelope.traceId,
                "invocationId": envelope.invocationId,
                "workflowExecutionId": envelope.workflowExecutionId,
            },
        )
        # mandatory=True: an unroutable message is returned to the publisher
        # instead of being silently dropped.
        await self._exchange.publish(message, routing_key=routing_key, mandatory=True)
        logger.info(
            "event published",
            extra={"routingKey": routing_key, "messageType": envelope.messageType.value},
        )
        return envelope

    def build_outcome_envelope(
        self,
        source: MessageEnvelope,
        message_type: MessageType,
        payload: dict[str, Any],
    ) -> MessageEnvelope:
        """Build the envelope of a result event derived from ``source``."""
        return build_outcome_envelope(source, message_type, payload)

    async def publish_outcome(
        self, source: MessageEnvelope, message_type: MessageType, payload: dict[str, Any]
    ) -> MessageEnvelope:
        """Publish an agent result event."""
        envelope = self.build_outcome_envelope(source, message_type, payload)
        self.validate(envelope)
        routing_key = ROUTING_BY_MESSAGE_TYPE.get(message_type, message_type.value)
        return await self.publish(envelope, routing_key)

    def validate(self, envelope: MessageEnvelope) -> None:
        """Validate an outgoing message against the shared JSON Schema files."""
        if not self._schema_registry.available:
            return
        try:
            self._schema_registry.validate_message(envelope)
        except Exception:
            logger.exception("outgoing message violates the protocol schema")
            raise
