"""Budget enforcement: iterations, tool calls, tokens and wall clock."""

from __future__ import annotations

import asyncio

from langgraph.graph import END, START, StateGraph

from app.messaging.events import AgentExecutePayload
from app.runtime.context import AgentExecutionContext
from app.runtime.executor import AgentExecutor
from app.runtime.registry import AgentGraphRegistry
from app.runtime.state import AgentState
from tests.conftest import make_execute_envelope


def _envelope_with_config(**config):
    envelope = make_execute_envelope()
    payload = {**envelope.payload, "config": {**envelope.payload["config"], **config}}
    return envelope.model_copy(update={"payload": payload})


async def test_max_iterations_is_enforced(executor):
    envelope = _envelope_with_config(maxIterations=1)
    outcome = await executor.execute(
        envelope, AgentExecutePayload.model_validate(envelope.payload)
    )

    assert outcome.status == "FAILED"
    assert outcome.payload["errorCode"] == "AG-BUDGET-001"
    assert outcome.payload["errorCategory"] == "BUSINESS"
    assert outcome.payload["retryable"] is False
    assert "maxIterations" in outcome.payload["errorMessage"]


async def test_max_tool_calls_is_enforced(executor, github_tool):
    envelope = _envelope_with_config(maxToolCalls=2)
    outcome = await executor.execute(
        envelope, AgentExecutePayload.model_validate(envelope.payload)
    )

    assert outcome.status == "FAILED"
    assert outcome.payload["errorCode"] == "AG-BUDGET-001"
    assert outcome.payload["errorCategory"] == "BUSINESS"
    assert len(github_tool.calls) == 2


async def test_max_tokens_is_enforced(executor):
    envelope = _envelope_with_config(maxTokens=1)
    outcome = await executor.execute(
        envelope, AgentExecutePayload.model_validate(envelope.payload)
    )

    assert outcome.status == "FAILED"
    assert outcome.payload["errorCode"] == "AG-BUDGET-001"
    assert outcome.payload["errorCategory"] == "BUSINESS"
    assert "maxTokens" in outcome.payload["errorMessage"]


async def test_the_wall_clock_budget_fails_the_invocation(
    settings, store, checkpointer, registry, journal
):
    """A hanging graph must not hang the service."""
    registry = AgentGraphRegistry()

    async def slow_node(state: AgentState, runtime) -> dict:
        await asyncio.sleep(30)
        return {"iterations": 1}

    graph = StateGraph(AgentState, context_schema=AgentExecutionContext)
    graph.add_node("slow", slow_node)
    graph.add_edge(START, "slow")
    graph.add_edge("slow", END)
    registry.register("SLOW_AGENT", lambda context: graph)

    executor = AgentExecutor(
        store=store,
        checkpointer=checkpointer,
        registry=registry,
        settings=settings,
        journal=journal,
    )

    envelope = _envelope_with_config(timeoutSeconds=1)
    payload = {**envelope.payload, "agentType": "SLOW_AGENT"}
    envelope = envelope.model_copy(update={"payload": payload})

    outcome = await executor.execute(
        envelope, AgentExecutePayload.model_validate(envelope.payload)
    )

    assert outcome.status == "FAILED"
    assert outcome.payload["errorCode"] == "AG-TIMEOUT-001"
    assert outcome.payload["errorCategory"] == "TRANSIENT"
    assert outcome.payload["retryable"] is True


async def test_the_usage_is_reported_in_the_completed_payload(executor):
    envelope = make_execute_envelope()
    outcome = await executor.execute(
        envelope, AgentExecutePayload.model_validate(envelope.payload)
    )

    assert outcome.status == "COMPLETED"
    usage = outcome.payload["usage"]
    assert usage["inputTokens"] > 0
    assert usage["outputTokens"] > 0
