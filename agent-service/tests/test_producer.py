"""The event publisher: persistent messages, publisher confirms, protocol."""

from __future__ import annotations

import json

from app.messaging.events import MessageType, build_envelope, new_trace_id
from app.messaging.producer import EventPublisher
from tests.conftest import make_execute_envelope


class FakeExchange:
    def __init__(self) -> None:
        self.published: list[tuple[object, str, bool]] = []

    async def publish(self, message, routing_key: str = "", mandatory: bool = False):
        self.published.append((message, routing_key, mandatory))
        return True


async def test_the_published_message_is_persistent_and_conformant(schema_registry):
    exchange = FakeExchange()
    publisher = EventPublisher(exchange=exchange, schema_registry=schema_registry)

    source = make_execute_envelope()
    envelope = build_envelope(
        MessageType.AGENT_COMPLETED,
        workflow_execution_id=source.workflowExecutionId,
        trace_id=source.traceId,
        payload={
            "invocationId": 30001,
            "threadId": "flowpilot-30001",
            "checkpointId": "checkpoint-001",
            "result": {"architectureAnalysis": "layered"},
            "usage": {"inputTokens": 10, "outputTokens": 5},
        },
        correlation_id=source.correlationId,
        causation_id=source.messageId,
        invocation_id=30001,
    )

    await publisher.publish(envelope, "agent.completed")

    message, routing_key, mandatory = exchange.published[0]
    assert routing_key == "agent.completed"
    assert mandatory is True
    assert message.delivery_mode.value == 2  # persistent
    assert message.content_type == "application/json"

    body = json.loads(message.body)
    schema_registry.validate_envelope(body)
    assert body["causationId"] == source.messageId
    assert body["correlationId"] == source.correlationId
    assert body["traceId"] == source.traceId
    assert body["messageType"] == "AgentCompleted"


async def test_the_publisher_validates_before_publishing(schema_registry):
    exchange = FakeExchange()
    publisher = EventPublisher(exchange=exchange, schema_registry=schema_registry)

    envelope = build_envelope(
        MessageType.AGENT_COMPLETED,
        workflow_execution_id=1,
        trace_id=new_trace_id(),
        payload={"invocationId": 1, "threadId": "flowpilot-1"},
    )
    await publisher.publish_outcome(envelope, MessageType.AGENT_COMPLETED, envelope.payload)

    _, routing_key, _ = exchange.published[0]
    assert routing_key == "agent.completed"


async def test_publishing_without_a_connection_fails_loudly():
    publisher = EventPublisher()
    envelope = build_envelope(
        MessageType.AGENT_COMPLETED,
        workflow_execution_id=1,
        trace_id=new_trace_id(),
        payload={"invocationId": 1, "threadId": "flowpilot-1"},
    )
    try:
        await publisher.publish(envelope, "agent.completed")
    except RuntimeError as exc:
        assert "not connected" in str(exc)
    else:  # pragma: no cover
        raise AssertionError("publishing without a connection must fail")
