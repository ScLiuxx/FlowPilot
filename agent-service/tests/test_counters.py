"""The statistics reported with the result of an invocation.

The Java backend counts the LLM calls, the tool calls and the tool failures to
measure the cost and the success rate of the agents (architecture design,
chapter 12).
"""

from __future__ import annotations

import pytest

from app.messaging.events import AgentExecutePayload
from app.runtime.context import AgentExecutionContext, ExecutionLimits
from tests.conftest import make_execute_envelope


async def test_the_usage_reports_the_llm_and_the_tool_calls(executor):
    envelope = make_execute_envelope()

    outcome = await executor.execute(
        envelope, AgentExecutePayload.model_validate(envelope.payload)
    )

    usage = outcome.payload["usage"]
    assert usage["llmCalls"] == 1
    # The six read only GitHub operations plus the closed issues, which the
    # issues endpoint does not return by default.
    assert usage["toolCalls"] == 7
    assert usage["toolFailures"] == 0


async def test_the_usage_reports_the_calls_of_the_dependency_analyst(executor):
    envelope = make_execute_envelope(agent_type="DEPENDENCY_ANALYST")
    payload = {
        **envelope.payload,
        "input": {
            "repository": "acme/widget",
            "manifests": {"requirements.txt": "requests==2.31.0\nflask\n"},
        },
    }
    envelope = envelope.model_copy(update={"payload": payload})

    outcome = await executor.execute(
        envelope, AgentExecutePayload.model_validate(envelope.payload)
    )

    usage = outcome.payload["usage"]
    assert usage["llmCalls"] == 1
    assert usage["toolCalls"] == 1
    assert usage["toolFailures"] == 0


async def test_a_failing_tool_is_counted_as_a_failure():
    context = AgentExecutionContext(
        invocation_id=1,
        thread_id="flowpilot-1",
        agent_type="REPOSITORY_ANALYST",
        trace_id="4bf92f3577b34da6a3ce929d0e0e4736",
        message_id="6f1c9a2e-4d3b-4a5f-9c8e-1b2d3e4f5a6b",
        limits=ExecutionLimits(),
    )

    def failing_tool() -> None:
        raise RuntimeError("the tool failed")

    with pytest.raises(RuntimeError):
        await context.call_tool(failing_tool)

    assert context.counters.tool_calls == 1
    assert context.counters.tool_failures == 1


async def test_the_counters_are_stored_in_the_record(executor, store):
    envelope = make_execute_envelope()

    await executor.execute(
        envelope, AgentExecutePayload.model_validate(envelope.payload)
    )

    record = await store.get(30001)
    assert record is not None
    assert record.usage == {
        "inputTokens": record.usage["inputTokens"],
        "outputTokens": record.usage["outputTokens"],
        "llmCalls": 1,
        "toolCalls": 7,
        "toolFailures": 0,
    }
