"""The AMQP consumer: manual ack, bounded requeue and dead lettering."""

from __future__ import annotations

import json

import pytest
from aio_pika import exceptions as aio_pika_exceptions

from app.messaging.consumer import AgentMessageConsumer
from app.messaging.events import MessageType
from tests.conftest import body_of, make_execute_envelope


class FakeMessage:
    def __init__(self, body: bytes, message_id: str | None = None) -> None:
        self.body = body
        self.message_id = message_id
        self.acked = False
        self.nacked = False
        self.rejected = False
        self.nack_requeue: bool | None = None
        self.reject_requeue: bool | None = None

    async def ack(self) -> None:
        self.acked = True

    async def nack(self, requeue: bool = True) -> None:
        self.nacked = True
        self.nack_requeue = requeue

    async def reject(self, requeue: bool = False) -> None:
        self.rejected = True
        self.reject_requeue = requeue


class FakePublisher:
    def __init__(self, error: Exception | None = None) -> None:
        self.published: list[tuple[MessageType, dict]] = []
        self.error = error

    async def publish_outcome(self, source, message_type, payload):
        if self.error is not None:
            raise self.error
        self.published.append((message_type, payload))

    async def connect(self) -> None:
        return None

    async def close(self) -> None:
        return None


@pytest.fixture
def consumer(executor, settings) -> AgentMessageConsumer:
    return AgentMessageConsumer(
        executor=executor, publisher=FakePublisher(), settings=settings
    )


async def test_a_valid_message_is_acked_after_the_work_succeeded(consumer):
    envelope = make_execute_envelope()
    message = FakeMessage(body_of(envelope), message_id=envelope.messageId)

    await consumer.handle(message)

    assert message.acked is True
    assert message.rejected is False
    assert len(consumer._publisher.published) == 1
    message_type, payload = consumer._publisher.published[0]
    assert message_type == MessageType.AGENT_COMPLETED
    assert payload["invocationId"] == 30001
    assert payload["threadId"] == "flowpilot-30001"


async def test_a_malformed_message_is_rejected_into_the_dead_letter_queue(consumer):
    message = FakeMessage(b"{not json", message_id="m-1")

    await consumer.handle(message)

    assert message.rejected is True
    assert message.reject_requeue is False
    assert message.acked is False


async def test_an_unsupported_protocol_version_is_rejected(consumer):
    envelope = make_execute_envelope()
    body = json.loads(body_of(envelope))
    body["schemaVersion"] = "2.0"
    message = FakeMessage(json.dumps(body).encode("utf-8"), message_id="m-2")

    await consumer.handle(message)

    assert message.rejected is True
    assert message.reject_requeue is False


async def test_a_payload_that_violates_the_schema_is_rejected(consumer):
    envelope = make_execute_envelope()
    body = json.loads(body_of(envelope))
    body["payload"]["agentType"] = 12345  # must be a string
    message = FakeMessage(json.dumps(body).encode("utf-8"), message_id="m-3")

    await consumer.handle(message)

    assert message.rejected is True


async def test_a_transient_broker_failure_is_requeued(executor, settings):
    publisher = FakePublisher(error=aio_pika_exceptions.AMQPConnectionError("broker down"))
    consumer = AgentMessageConsumer(executor=executor, publisher=publisher, settings=settings)
    envelope = make_execute_envelope()
    message = FakeMessage(body_of(envelope), message_id=envelope.messageId)

    await consumer.handle(message)

    assert message.nacked is True
    assert message.nack_requeue is True
    assert message.acked is False


async def test_the_requeue_is_bounded_and_ends_in_the_dead_letter_queue(executor, settings):
    publisher = FakePublisher(error=aio_pika_exceptions.AMQPConnectionError("broker down"))
    consumer = AgentMessageConsumer(executor=executor, publisher=publisher, settings=settings)
    envelope = make_execute_envelope()

    for _ in range(settings.rabbitmq_max_redeliveries):
        message = FakeMessage(body_of(envelope), message_id=envelope.messageId)
        await consumer.handle(message)
        assert message.nacked is True

    message = FakeMessage(body_of(envelope), message_id=envelope.messageId)
    await consumer.handle(message)
    assert message.rejected is True
    assert message.reject_requeue is False
