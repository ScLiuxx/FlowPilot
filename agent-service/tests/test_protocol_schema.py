"""The outgoing messages must conform to the shared JSON Schema files.

The very same files are loaded by the Java backend, so this test is the
contract guard of the protocol.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest
from jsonschema import Draft7Validator

from app.messaging.events import (
    AgentCompletedPayload,
    AgentExecutePayload,
    AgentFailedPayload,
    AgentInterruptedPayload,
    AgentResumePayload,
    ErrorPayload,
    InterruptData,
    MessageType,
    TokenUsage,
    build_envelope,
    dump_payload,
)
from tests.conftest import PROTOCOL_DIR, make_execute_envelope, make_resume_envelope


def _load(relative: str) -> dict:
    return json.loads((PROTOCOL_DIR / relative).read_text(encoding="utf-8"))


def _validate(relative: str, instance: dict) -> None:
    schema = _load(relative)
    Draft7Validator.check_schema(schema)
    errors = sorted(Draft7Validator(schema).iter_errors(instance), key=str)
    assert not errors, "; ".join(f"{list(e.path)}: {e.message}" for e in errors)


def test_envelope_schema_matches_the_shared_file(schema_registry):
    envelope = make_execute_envelope()
    data = envelope.model_dump(mode="json")
    _validate("common/envelope.schema.json", data)
    schema_registry.validate_envelope(envelope)


def test_envelope_rejects_unknown_fields():
    from app.messaging.events import MessageEnvelope

    with pytest.raises(Exception):
        MessageEnvelope.model_validate(
            {
                "schemaVersion": "1.0",
                "messageId": "550e8400-e29b-41d4-a716-446655440000",
                "messageType": "AgentExecute",
                "correlationId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
                "workflowExecutionId": 10001,
                "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
                "timestamp": "2026-11-11T10:00:00.000Z",
                "producer": "flowpilot-backend",
                "payload": {},
                "unexpected": True,
            }
        )


@pytest.mark.parametrize(
    "message_type,payload,relative",
    [
        (
            MessageType.AGENT_EXECUTE,
            AgentExecutePayload(
                agentType="REPOSITORY_ANALYST",
                threadId="flowpilot-30001",
                invocationId=30001,
                input={"repository": {"fullName": "example/project", "stars": 12000}},
                config={
                    "model": "configured-model",
                    "maxIterations": 10,
                    "maxToolCalls": 20,
                    "timeoutSeconds": 300,
                },
            ),
            "agent/agent-execute.schema.json",
        ),
        (
            MessageType.AGENT_COMPLETED,
            AgentCompletedPayload(
                invocationId=30001,
                threadId="flowpilot-30001",
                checkpointId="checkpoint-001",
                result={
                    "architectureAnalysis": "项目采用分层架构...",
                    "maintenanceAnalysis": "项目近期仍有活跃维护...",
                    "riskAnalysis": "需要进一步关注依赖风险...",
                    "recommendation": "建议继续技术评估",
                },
                usage=TokenUsage(inputTokens=5000, outputTokens=2000),
            ),
            "agent/agent-completed.schema.json",
        ),
        (
            MessageType.AGENT_FAILED,
            AgentFailedPayload(
                invocationId=30001,
                threadId="flowpilot-30001",
                errorCode="LLM_REQUEST_TIMEOUT",
                errorCategory="TRANSIENT",
                errorMessage="Model request timed out",
                retryable=True,
            ),
            "agent/agent-failed.schema.json",
        ),
        (
            MessageType.AGENT_INTERRUPTED,
            AgentInterruptedPayload(
                invocationId=30001,
                threadId="flowpilot-30001",
                checkpointId="checkpoint-002",
                interruptType="HUMAN_APPROVAL",
                interruptData=InterruptData(
                    title="请审核技术尽调报告",
                    description="Agent 已完成初步分析，需要人工确认。",
                    requestedAction="APPROVE_REPORT",
                ),
            ),
            "agent/agent-interrupted.schema.json",
        ),
        (
            MessageType.AGENT_RESUME,
            AgentResumePayload(
                invocationId=30001,
                threadId="flowpilot-30001",
                resumeRequestId="resume-001",
                approvalId=40001,
                resumeValue={"approved": True, "comment": "报告内容符合要求"},
            ),
            "agent/agent-resume.schema.json",
        ),
    ],
)
def test_payload_schemas_match_the_shared_files(
    schema_registry, message_type, payload, relative
):
    data = dump_payload(payload)
    _validate(relative, data)
    schema_registry.validate_payload(message_type, payload)


def test_error_payload_matches_the_shared_file():
    payload = ErrorPayload(
        errorCode="GITHUB_API_TIMEOUT",
        errorCategory="TRANSIENT",
        errorMessage="GitHub API timed out",
        retryable=True,
        diagnostics={"status": 504},
    )
    _validate("common/error.schema.json", payload.model_dump(mode="json"))


def test_the_documented_envelope_example_is_valid(schema_registry):
    """The example of the protocol README must validate."""
    example = {
        "schemaVersion": "1.0",
        "messageId": "550e8400-e29b-41d4-a716-446655440000",
        "messageType": "AgentExecute",
        "correlationId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
        "causationId": None,
        "workflowExecutionId": 10001,
        "nodeExecutionId": 20001,
        "attemptNo": 1,
        "invocationId": 30001,
        "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
        "timestamp": "2026-11-11T10:00:00.000Z",
        "producer": "flowpilot-backend",
        "payload": {
            "agentType": "REPOSITORY_ANALYST",
            "threadId": "flowpilot-30001",
            "invocationId": 30001,
            "input": {},
            "config": {"maxIterations": 10},
        },
    }
    _validate("common/envelope.schema.json", example)
    _validate("agent/agent-execute.schema.json", example["payload"])


async def test_outgoing_events_validate_against_the_schema(schema_registry, executor):
    """The events produced by the executor are protocol conformant."""
    envelope = make_execute_envelope()
    result = await executor.execute(
        envelope, AgentExecutePayload.model_validate(envelope.payload)
    )
    event = build_envelope(
        result.message_type,
        workflow_execution_id=envelope.workflowExecutionId,
        trace_id=envelope.traceId,
        payload=result.payload,
        correlation_id=envelope.correlationId,
        causation_id=envelope.messageId,
        node_execution_id=envelope.nodeExecutionId,
        attempt_no=envelope.attemptNo,
        invocation_id=envelope.invocationId,
    )
    schema_registry.validate_message(event)
    _validate("common/envelope.schema.json", event.model_dump(mode="json"))
    _validate("agent/agent-completed.schema.json", event.payload)


def test_resume_envelope_validates(schema_registry):
    envelope = make_resume_envelope()
    schema_registry.validate_message(envelope)


def test_protocol_directory_contains_the_expected_files():
    for relative in (
        "common/envelope.schema.json",
        "common/error.schema.json",
        "agent/agent-execute.schema.json",
        "agent/agent-resume.schema.json",
        "agent/agent-cancel.schema.json",
        "agent/agent-completed.schema.json",
        "agent/agent-failed.schema.json",
        "agent/agent-interrupted.schema.json",
    ):
        assert (Path(PROTOCOL_DIR) / relative).is_file()
