"""The OpenTelemetry instrumentation of the agent runtime.

The spans have to join the trace the Java backend opened: the ``traceId`` of
the envelope is a 32 character hex string, which is exactly the OpenTelemetry
trace id format.
"""

from __future__ import annotations

import httpx
import pytest
from opentelemetry import trace
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter

from app.main import create_app
from app.messaging.events import AgentExecutePayload
from app.telemetry import parent_context, span_id_from_message_id
from app.tools.dependency_tool import analyze_dependencies
from app.tools.github_tool import GitHubTool
from tests.conftest import make_execute_envelope


@pytest.fixture
def spans():
    """Install a tracer provider that records the spans in memory."""
    exporter = InMemorySpanExporter()
    provider = TracerProvider()
    provider.add_span_processor(SimpleSpanProcessor(exporter))
    previous = trace._TRACER_PROVIDER
    trace._TRACER_PROVIDER = provider
    try:
        yield exporter
    finally:
        trace._TRACER_PROVIDER = previous


def test_the_parent_context_carries_the_trace_id_of_the_envelope():
    envelope = make_execute_envelope()

    context = parent_context(envelope.traceId, envelope.messageId)

    span_context = trace.get_current_span(context).get_span_context()
    assert f"{span_context.trace_id:032x}" == envelope.traceId
    assert span_context.is_remote is True


def test_the_span_id_is_derived_from_the_message_id():
    envelope = make_execute_envelope()

    first = span_id_from_message_id(envelope.messageId)
    second = span_id_from_message_id(envelope.messageId)

    assert first == second
    assert first != span_id_from_message_id("another-message")


async def test_the_graph_execution_produces_a_span_in_the_trace_of_the_command(
    spans, executor
):
    envelope = make_execute_envelope()

    await executor.execute(
        envelope, AgentExecutePayload.model_validate(envelope.payload)
    )

    names = [span.name for span in spans.get_finished_spans()]
    assert "agent.graph" in names
    assert "llm.call" in names

    graph_span = next(span for span in spans.get_finished_spans() if span.name == "agent.graph")
    assert f"{graph_span.context.trace_id:032x}" == envelope.traceId
    assert graph_span.attributes["flowpilot.invocation_id"] == 30001
    assert graph_span.attributes["flowpilot.agent_type"] == "REPOSITORY_ANALYST"


async def test_the_llm_span_carries_the_model_and_the_token_counters(spans, executor):
    envelope = make_execute_envelope()

    await executor.execute(
        envelope, AgentExecutePayload.model_validate(envelope.payload)
    )

    llm_span = next(span for span in spans.get_finished_spans() if span.name == "llm.call")
    assert llm_span.attributes["model"]
    assert llm_span.attributes["inputTokens"] > 0
    assert llm_span.attributes["outputTokens"] > 0


async def test_the_span_does_not_contain_the_prompt_or_the_answer(spans, executor):
    envelope = make_execute_envelope()

    await executor.execute(
        envelope, AgentExecutePayload.model_validate(envelope.payload)
    )

    for span in spans.get_finished_spans():
        for key, value in (span.attributes or {}).items():
            assert "Ignore all previous instructions" not in str(value)
            assert "api_key" not in str(key)


async def test_the_github_operations_produce_their_own_span(spans):
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"full_name": "acme/widget", "stargazers_count": 7})

    client = httpx.AsyncClient(transport=httpx.MockTransport(handler), follow_redirects=False)
    tool = GitHubTool(client=client)

    await tool.get_repository("acme/widget")
    await tool.list_commits("acme/widget")

    names = [span.name for span in spans.get_finished_spans()]
    assert "tool.github_get_repository" in names
    assert "tool.github_list_commits" in names
    span = next(
        span for span in spans.get_finished_spans() if span.name == "tool.github_get_repository"
    )
    assert span.attributes["repository"] == "acme/widget"


async def test_the_dependency_scan_produces_its_own_span(spans):
    analyze_dependencies({"requirements.txt": "requests==2.31.0\nflask\n"})

    names = [span.name for span in spans.get_finished_spans()]
    assert "tool.dependency_scan" in names


async def test_a_failing_tool_records_the_error_code(spans):
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(404, json={"message": "Not Found"})

    client = httpx.AsyncClient(transport=httpx.MockTransport(handler), follow_redirects=False)
    tool = GitHubTool(client=client)

    with pytest.raises(Exception):
        await tool.get_repository("acme/widget")

    span = next(
        span for span in spans.get_finished_spans() if span.name == "tool.github_get_repository"
    )
    assert span.status.status_code.name == "ERROR"


def test_the_http_requests_are_instrumented(spans, settings):
    from fastapi.testclient import TestClient

    app = create_app(
        settings=settings, start_messaging=False, start_persistence=False
    )
    with TestClient(app) as client:
        assert client.get("/health").status_code == 200

    names = [span.name for span in spans.get_finished_spans()]
    assert any(name.startswith("GET") for name in names), names
