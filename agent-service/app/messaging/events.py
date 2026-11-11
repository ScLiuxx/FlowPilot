"""Unified message envelope and payload models.

The models in this module are a 1:1 mirror of the JSON Schema files that live
in ``flowpilot-protocol/``. The Java backend loads the very same files, so the
contract cannot drift silently. Every outgoing message is additionally
validated against those schema files at runtime (see
:class:`ProtocolSchemaRegistry`).
"""

from __future__ import annotations

import json
import re
import uuid
from datetime import datetime, timezone
from enum import Enum
from pathlib import Path
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, field_serializer, field_validator

SCHEMA_VERSION = "1.0"
PRODUCER = "flowpilot-agent-service"

_VERSION_PATTERN = re.compile(r"^\d+\.\d+$")
_TRACE_ID_PATTERN = re.compile(r"^[0-9a-f]{32}$")

#: Routing keys used by the agent service.
ROUTING_AGENT_EXECUTE = "agent.execute"
ROUTING_AGENT_RESUME = "agent.resume"
ROUTING_AGENT_CANCEL = "agent.cancel"
ROUTING_AGENT_COMPLETED = "agent.completed"
ROUTING_AGENT_FAILED = "agent.failed"
ROUTING_AGENT_INTERRUPTED = "agent.interrupted"


def utc_now() -> datetime:
    return datetime.now(tz=timezone.utc)


def format_timestamp(value: datetime) -> str:
    """ISO-8601 UTC with millisecond precision (``2026-11-11T10:00:00.000Z``)."""
    if value.tzinfo is None:
        value = value.replace(tzinfo=timezone.utc)
    value = value.astimezone(timezone.utc)
    return value.isoformat(timespec="milliseconds").replace("+00:00", "Z")


def new_message_id() -> str:
    return str(uuid.uuid4())


def new_trace_id() -> str:
    """32 hex chars — compatible with the OpenTelemetry trace id format."""
    return uuid.uuid4().hex


class MessageType(str, Enum):
    WORKFLOW_STARTED = "WorkflowStarted"
    NODE_EXECUTE = "NodeExecute"
    NODE_COMPLETED = "NodeCompleted"
    NODE_FAILED = "NodeFailed"
    AGENT_EXECUTE = "AgentExecute"
    AGENT_RESUME = "AgentResume"
    AGENT_CANCEL = "AgentCancel"
    AGENT_COMPLETED = "AgentCompleted"
    AGENT_FAILED = "AgentFailed"
    AGENT_INTERRUPTED = "AgentInterrupted"


class ErrorCategory(str, Enum):
    """Mirrors ``errorCategory`` of ``common/error.schema.json``."""

    TRANSIENT = "TRANSIENT"
    RATE_LIMIT = "RATE_LIMIT"
    VALIDATION = "VALIDATION"
    PERMISSION = "PERMISSION"
    BUSINESS = "BUSINESS"
    INTERNAL = "INTERNAL"


class ErrorPayload(BaseModel):
    """Mirrors ``common/error.schema.json``."""

    model_config = ConfigDict(extra="forbid")

    errorCode: str
    errorCategory: ErrorCategory
    errorMessage: str
    retryable: bool
    diagnostics: dict[str, Any] | None = None


class TokenUsage(BaseModel):
    model_config = ConfigDict(extra="forbid")

    inputTokens: int = 0
    outputTokens: int = 0

    @property
    def total(self) -> int:
        return self.inputTokens + self.outputTokens


class MessageEnvelope(BaseModel):
    """Uniform AMQP envelope — mirrors ``common/envelope.schema.json``."""

    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    schemaVersion: str = SCHEMA_VERSION
    messageId: str
    messageType: MessageType
    correlationId: str
    causationId: str | None = None
    workflowExecutionId: int
    nodeExecutionId: int | None = None
    attemptNo: int | None = None
    invocationId: int | None = None
    traceId: str
    timestamp: datetime
    producer: str = PRODUCER
    payload: dict[str, Any] = Field(default_factory=dict)

    @field_validator("schemaVersion")
    @classmethod
    def _check_schema_version(cls, value: str) -> str:
        if not _VERSION_PATTERN.match(value):
            raise ValueError("schemaVersion must match ^\\d+\\.\\d+$")
        return value

    @field_validator("messageId")
    @classmethod
    def _check_message_id(cls, value: str) -> str:
        # uuid format check without raising for non canonical forms
        uuid.UUID(value)
        return value

    @field_validator("traceId")
    @classmethod
    def _check_trace_id(cls, value: str) -> str:
        if not _TRACE_ID_PATTERN.match(value):
            raise ValueError("traceId must be 32 lower case hex characters")
        return value

    @field_validator("timestamp")
    @classmethod
    def _check_timestamp(cls, value: datetime) -> datetime:
        if value.tzinfo is None:
            raise ValueError("timestamp must carry a timezone")
        return value.astimezone(timezone.utc)

    @field_serializer("timestamp")
    def _serialize_timestamp(self, value: datetime) -> str:
        return format_timestamp(value)

    @field_serializer("messageType")
    def _serialize_message_type(self, value: MessageType) -> str:
        return value.value

    @field_serializer("payload")
    def _serialize_payload(self, value: dict[str, Any]) -> dict[str, Any]:
        return value


# ---------------------------------------------------------------------------
# Payload models (mirror flowpilot-protocol/agent/*.schema.json)
# ---------------------------------------------------------------------------


class AgentExecuteConfig(BaseModel):
    model_config = ConfigDict(extra="allow")

    model: str | None = None
    maxIterations: int | None = None
    maxToolCalls: int | None = None
    maxTokens: int | None = None
    timeoutSeconds: int | None = None


class AgentExecutePayload(BaseModel):
    model_config = ConfigDict(extra="allow")

    agentType: str
    threadId: str
    invocationId: int
    input: dict[str, Any] = Field(default_factory=dict)
    config: AgentExecuteConfig = Field(default_factory=AgentExecuteConfig)


class AgentResumePayload(BaseModel):
    model_config = ConfigDict(extra="allow")

    invocationId: int
    threadId: str
    resumeRequestId: str
    approvalId: int | None = None
    resumeValue: dict[str, Any] = Field(default_factory=dict)


class AgentCancelPayload(BaseModel):
    model_config = ConfigDict(extra="allow")

    invocationId: int
    threadId: str
    cancelRequestId: str | None = None
    cancelReason: str | None = None


class AgentCompletedPayload(BaseModel):
    model_config = ConfigDict(extra="allow")

    invocationId: int
    threadId: str
    checkpointId: str | None = None
    result: dict[str, Any] = Field(default_factory=dict)
    usage: TokenUsage | None = None


class AgentFailedPayload(BaseModel):
    model_config = ConfigDict(extra="allow")

    invocationId: int
    threadId: str
    checkpointId: str | None = None
    errorCode: str
    errorCategory: ErrorCategory
    errorMessage: str
    retryable: bool
    diagnostics: dict[str, Any] | None = None


class InterruptData(BaseModel):
    model_config = ConfigDict(extra="allow")

    title: str | None = None
    description: str | None = None
    requestedAction: str | None = None
    payload: dict[str, Any] = Field(default_factory=dict)


class AgentInterruptedPayload(BaseModel):
    model_config = ConfigDict(extra="allow")

    invocationId: int
    threadId: str
    checkpointId: str | None = None
    interruptType: str = "HUMAN_APPROVAL"
    interruptData: InterruptData = Field(default_factory=InterruptData)


# ---------------------------------------------------------------------------
# Envelope helpers
# ---------------------------------------------------------------------------


def dump_payload(payload: BaseModel) -> dict[str, Any]:
    """Serialise a payload model.

    ``None`` values are dropped: the shared JSON Schema files declare optional
    fields as *absent* (a present ``null`` would violate e.g. the
    ``maxTokens`` definition of ``agent-execute.schema.json``).
    """
    return payload.model_dump(mode="json", exclude_none=True)


def build_envelope(
    message_type: MessageType,
    *,
    workflow_execution_id: int,
    trace_id: str,
    payload: BaseModel | dict[str, Any],
    correlation_id: str | None = None,
    causation_id: str | None = None,
    node_execution_id: int | None = None,
    attempt_no: int | None = None,
    invocation_id: int | None = None,
    producer: str = PRODUCER,
    schema_version: str = SCHEMA_VERSION,
    message_id: str | None = None,
    timestamp: datetime | None = None,
) -> MessageEnvelope:
    """Create a protocol conformant envelope."""
    if isinstance(payload, BaseModel):
        payload_dict = dump_payload(payload)
    else:
        payload_dict = payload
    return MessageEnvelope(
        schemaVersion=schema_version,
        messageId=message_id or new_message_id(),
        messageType=message_type,
        correlationId=correlation_id or new_message_id(),
        causationId=causation_id,
        workflowExecutionId=workflow_execution_id,
        nodeExecutionId=node_execution_id,
        attemptNo=attempt_no,
        invocationId=invocation_id,
        traceId=trace_id,
        timestamp=timestamp or utc_now(),
        producer=producer,
        payload=payload_dict,
    )


def envelope_to_dict(envelope: MessageEnvelope) -> dict[str, Any]:
    """Serialise an envelope to the exact wire format of the protocol."""
    return envelope.model_dump(mode="json")


def parse_envelope(raw: bytes | str | dict[str, Any]) -> MessageEnvelope:
    """Parse and validate an incoming envelope."""
    if isinstance(raw, (bytes, bytearray)):
        raw = raw.decode("utf-8")
    data = json.loads(raw) if isinstance(raw, str) else raw
    return MessageEnvelope.model_validate(data)


# ---------------------------------------------------------------------------
# JSON Schema validation against the shared protocol files
# ---------------------------------------------------------------------------


class ProtocolViolation(ValueError):
    """Raised when a message does not conform to the shared JSON Schema."""


def _default_protocol_dir() -> Path | None:
    candidates: list[Path] = []
    here = Path(__file__).resolve()
    # <repo>/agent-service/app/messaging/events.py -> <repo>/flowpilot-protocol
    candidates.append(here.parents[3] / "flowpilot-protocol")
    # container layout: /app/flowpilot-protocol
    candidates.append(Path("/app/flowpilot-protocol"))
    candidates.append(Path("/workspace/flowpilot-protocol"))
    for candidate in candidates:
        if candidate.is_dir():
            return candidate
    return None


class ProtocolSchemaRegistry:
    """Loads the shared JSON Schema files and validates messages against them."""

    _PAYLOAD_FILES: dict[MessageType, str] = {
        MessageType.AGENT_EXECUTE: "agent/agent-execute.schema.json",
        MessageType.AGENT_RESUME: "agent/agent-resume.schema.json",
        MessageType.AGENT_CANCEL: "agent/agent-cancel.schema.json",
        MessageType.AGENT_COMPLETED: "agent/agent-completed.schema.json",
        MessageType.AGENT_FAILED: "agent/agent-failed.schema.json",
        MessageType.AGENT_INTERRUPTED: "agent/agent-interrupted.schema.json",
    }

    def __init__(self, base_dir: Path | str | None = None) -> None:
        self.base_dir = Path(base_dir) if base_dir is not None else _default_protocol_dir()
        self._cache: dict[str, dict[str, Any]] = {}
        self._validators: dict[str, Any] = {}

    @property
    def available(self) -> bool:
        return self.base_dir is not None and self.base_dir.is_dir()

    def _load(self, relative_path: str) -> dict[str, Any]:
        if relative_path not in self._cache:
            if self.base_dir is None:
                raise ProtocolViolation("protocol schema directory is not configured")
            path = self.base_dir / relative_path
            if not path.is_file():
                raise ProtocolViolation(f"protocol schema file missing: {path}")
            self._cache[relative_path] = json.loads(path.read_text(encoding="utf-8"))
        return self._cache[relative_path]

    def _validator(self, relative_path: str):
        if relative_path not in self._validators:
            from jsonschema import Draft7Validator

            self._validators[relative_path] = Draft7Validator(self._load(relative_path))
        return self._validators[relative_path]

    def envelope_schema(self) -> dict[str, Any]:
        return self._load("common/envelope.schema.json")

    def payload_schema(self, message_type: MessageType) -> dict[str, Any]:
        relative = self._PAYLOAD_FILES.get(message_type)
        if relative is None:
            raise ProtocolViolation(f"no payload schema for message type {message_type}")
        return self._load(relative)

    def validate_envelope(self, envelope: MessageEnvelope | dict[str, Any]) -> None:
        data = envelope_to_dict(envelope) if isinstance(envelope, MessageEnvelope) else envelope
        errors = sorted(self._validator("common/envelope.schema.json").iter_errors(data), key=str)
        if errors:
            raise ProtocolViolation(
                "envelope does not conform to envelope.schema.json: "
                + "; ".join(f"{list(e.path)}: {e.message}" for e in errors)
            )

    def validate_payload(
        self, message_type: MessageType, payload: BaseModel | dict[str, Any]
    ) -> None:
        if isinstance(payload, BaseModel):
            data = dump_payload(payload)
        else:
            data = payload
        relative = self._PAYLOAD_FILES[message_type]
        errors = sorted(self._validator(relative).iter_errors(data), key=str)
        if errors:
            raise ProtocolViolation(
                f"payload does not conform to {relative}: "
                + "; ".join(f"{list(e.path)}: {e.message}" for e in errors)
            )

    def validate_message(
        self, envelope: MessageEnvelope | dict[str, Any]
    ) -> None:
        """Validate envelope *and* the payload of an agent message."""
        data = envelope_to_dict(envelope) if isinstance(envelope, MessageEnvelope) else envelope
        self.validate_envelope(data)
        message_type = MessageType(data["messageType"])
        relative = self._PAYLOAD_FILES.get(message_type)
        if relative is not None:
            errors = sorted(self._validator(relative).iter_errors(data["payload"]), key=str)
            if errors:
                raise ProtocolViolation(
                    f"payload does not conform to {relative}: "
                    + "; ".join(f"{list(e.path)}: {e.message}" for e in errors)
                )
