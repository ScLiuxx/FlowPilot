"""The unified envelope model."""

from __future__ import annotations

import re
from datetime import datetime, timezone

import pytest

from app.messaging.events import (
    SCHEMA_VERSION,
    MessageEnvelope,
    MessageType,
    build_envelope,
    envelope_to_dict,
    new_trace_id,
    parse_envelope,
)

TIMESTAMP_RE = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$")


def _envelope(**kwargs) -> MessageEnvelope:
    data = {
        "messageId": "550e8400-e29b-41d4-a716-446655440000",
        "messageType": MessageType.AGENT_EXECUTE,
        "correlationId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
        "workflowExecutionId": 10001,
        "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
        "timestamp": datetime(2026, 11, 11, 10, 0, 0, tzinfo=timezone.utc),
        "producer": "flowpilot-backend",
        "payload": {"agentType": "REPOSITORY_ANALYST", "threadId": "flowpilot-30001", "invocationId": 30001},
    }
    data.update(kwargs)
    return MessageEnvelope(**data)


def test_the_timestamp_is_serialised_with_millisecond_precision():
    data = envelope_to_dict(_envelope())
    assert TIMESTAMP_RE.match(data["timestamp"]), data["timestamp"]


def test_the_default_schema_version_is_used():
    assert _envelope().schemaVersion == SCHEMA_VERSION


def test_an_invalid_trace_id_is_rejected():
    with pytest.raises(Exception):
        _envelope(traceId="not-a-trace-id")


def test_an_invalid_schema_version_is_rejected():
    with pytest.raises(Exception):
        _envelope(schemaVersion="one.zero")


def test_a_naive_timestamp_is_rejected():
    with pytest.raises(Exception):
        _envelope(timestamp=datetime(2026, 11, 11, 10, 0, 0))


def test_the_envelope_round_trips():
    envelope = _envelope()
    parsed = parse_envelope(envelope_to_dict(envelope))
    assert parsed.messageId == envelope.messageId
    assert parsed.messageType == envelope.messageType
    assert parsed.payload == envelope.payload


def test_build_envelope_generates_valid_identifiers():
    envelope = build_envelope(
        MessageType.AGENT_COMPLETED,
        workflow_execution_id=10001,
        trace_id=new_trace_id(),
        payload={"invocationId": 30001},
    )
    assert re.match(r"^[0-9a-f]{32}$", envelope.traceId)
    assert envelope.messageId != envelope.correlationId
    assert envelope.timestamp.tzinfo is not None
