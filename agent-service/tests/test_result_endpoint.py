"""The endpoint the Java backend queries to recover an invocation.

The agent side is the authority for the result of an invocation: the business
database of the backend may still show the node as running (database design,
chapter 6.2).
"""

from __future__ import annotations

import httpx

from app.main import create_app
from app.persistence.repository import (
    STATUS_FAILED,
    STATUS_INTERRUPTED,
    STATUS_RUNNING,
    STATUS_SUCCEEDED,
    OutboxMessage,
)


async def _client(app):
    transport = httpx.ASGITransport(app=app)
    return httpx.AsyncClient(transport=transport, base_url="http://testserver")


async def test_an_unknown_invocation_is_not_found(settings):
    app = create_app(settings=settings, start_messaging=False, start_persistence=False)
    async with app.router.lifespan_context(app):
        async with await _client(app) as client:
            response = await client.get("/internal/invocations/424242/result")

    assert response.status_code == 404


async def test_a_succeeded_invocation_reports_its_result(settings):
    app = create_app(settings=settings, start_messaging=False, start_persistence=False)
    async with app.router.lifespan_context(app):
        store = app.state.store
        await store.create(
            invocation_id=30001,
            thread_id="flowpilot-30001",
            agent_type="REPOSITORY_ANALYST",
        )
        await store.update_with_outcome(
            30001,
            status=STATUS_SUCCEEDED,
            checkpoint_id="checkpoint-1",
            result={
                "invocationId": 30001,
                "threadId": "flowpilot-30001",
                "checkpointId": "checkpoint-1",
                "result": {"architectureAnalysis": "layered"},
                "usage": {"inputTokens": 10, "outputTokens": 4},
            },
            usage={"inputTokens": 10, "outputTokens": 4, "llmCalls": 1, "toolCalls": 6},
        )

        async with await _client(app) as client:
            response = await client.get("/internal/invocations/30001/result")

    body = response.json()
    assert response.status_code == 200
    assert body["invocationId"] == 30001
    assert body["threadId"] == "flowpilot-30001"
    assert body["agentType"] == "REPOSITORY_ANALYST"
    assert body["status"] == "SUCCEEDED"
    assert body["checkpointId"] == "checkpoint-1"
    assert body["result"] == {"architectureAnalysis": "layered"}
    assert body["error"] is None
    assert body["usage"] == {
        "inputTokens": 10,
        "outputTokens": 4,
        "llmCalls": 1,
        "toolCalls": 6,
        "toolFailures": 0,
    }
    assert body["updatedAt"]


async def test_a_running_invocation_is_reported_as_running(settings):
    app = create_app(settings=settings, start_messaging=False, start_persistence=False)
    async with app.router.lifespan_context(app):
        store = app.state.store
        await store.create(
            invocation_id=30001,
            thread_id="flowpilot-30001",
            agent_type="REPOSITORY_ANALYST",
            status=STATUS_RUNNING,
        )

        async with await _client(app) as client:
            response = await client.get("/internal/invocations/30001/result")

    body = response.json()
    assert body["status"] == "RUNNING"
    assert body["result"] is None
    assert body["usage"] == {
        "inputTokens": 0,
        "outputTokens": 0,
        "llmCalls": 0,
        "toolCalls": 0,
        "toolFailures": 0,
    }


async def test_a_failed_invocation_reports_its_error(settings):
    app = create_app(settings=settings, start_messaging=False, start_persistence=False)
    async with app.router.lifespan_context(app):
        store = app.state.store
        await store.create(
            invocation_id=30001,
            thread_id="flowpilot-30001",
            agent_type="REPOSITORY_ANALYST",
        )
        await store.update_with_outcome(
            30001,
            status=STATUS_FAILED,
            error={
                "invocationId": 30001,
                "threadId": "flowpilot-30001",
                "errorCode": "AG-BUDGET-001",
                "errorCategory": "BUSINESS",
                "errorMessage": "maxIterations exceeded",
                "retryable": False,
            },
        )

        async with await _client(app) as client:
            response = await client.get("/internal/invocations/30001/result")

    body = response.json()
    assert body["status"] == "FAILED"
    assert body["error"]["errorCode"] == "AG-BUDGET-001"
    assert body["result"] is None


async def test_an_interrupted_invocation_is_reported_as_interrupted(settings):
    app = create_app(settings=settings, start_messaging=False, start_persistence=False)
    async with app.router.lifespan_context(app):
        store = app.state.store
        await store.create(
            invocation_id=30001,
            thread_id="flowpilot-30001",
            agent_type="REPORT_REVIEWER",
        )
        await store.update_with_outcome(
            30001,
            status=STATUS_INTERRUPTED,
            checkpoint_id="checkpoint-2",
            outbox=OutboxMessage(
                message_id="6f1c9a2e-4d3b-4a5f-9c8e-1b2d3e4f5a6b",
                message_type="AgentInterrupted",
                routing_key="agent.interrupted",
                payload={},
            ),
        )

        async with await _client(app) as client:
            response = await client.get("/internal/invocations/30001/result")

    body = response.json()
    assert body["status"] == "INTERRUPTED"
    assert body["checkpointId"] == "checkpoint-2"


async def test_the_result_endpoint_is_protected_by_the_internal_token(settings):
    settings.internal_api_token = "s3cret"
    app = create_app(settings=settings, start_messaging=False, start_persistence=False)
    async with app.router.lifespan_context(app):
        async with await _client(app) as client:
            denied = await client.get("/internal/invocations/30001/result")
            allowed = await client.get(
                "/internal/invocations/30001/result",
                headers={"Authorization": "Bearer s3cret"},
            )

    assert denied.status_code == 401
    assert allowed.status_code == 404
