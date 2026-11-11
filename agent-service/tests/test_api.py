"""The HTTP surface: health, readiness and the internal debug endpoints."""

from __future__ import annotations

from fastapi.testclient import TestClient

from app.main import create_app
from app.runtime.registry import register_default_graphs, AgentGraphRegistry


def _client(settings) -> TestClient:
    app = create_app(
        settings=settings,
        start_messaging=False,
        start_persistence=False,
    )
    return TestClient(app)


def test_health_is_up(settings):
    with _client(settings) as client:
        response = client.get("/health")
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "UP"
    assert body["service"] == settings.service_name


def test_ready_reports_the_disabled_dependencies(settings):
    with _client(settings) as client:
        response = client.get("/ready")
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "UP"
    assert body["checks"]["rabbitmq"] == "DISABLED"
    assert body["checks"]["postgres"] == "DISABLED"


def test_the_internal_agent_list_is_exposed(settings):
    with _client(settings) as client:
        response = client.get("/internal/agents")
    assert response.status_code == 200
    agent_types = {item["agentType"] for item in response.json()["agents"]}
    assert {"REPOSITORY_ANALYST", "DEPENDENCY_ANALYST", "REPORT_REVIEWER"} <= agent_types


def test_an_unknown_invocation_is_not_found(settings):
    with _client(settings) as client:
        response = client.get("/internal/invocations/424242")
    assert response.status_code == 404


def test_the_internal_api_token_is_enforced(settings):
    settings.internal_api_token = "s3cret"
    with _client(settings) as client:
        assert client.get("/internal/agents").status_code == 401
        ok = client.get("/internal/agents", headers={"Authorization": "Bearer s3cret"})
        assert ok.status_code == 200


def test_the_registry_contains_the_default_graphs():
    registry = register_default_graphs(AgentGraphRegistry())
    assert registry.agent_types() == [
        "DEPENDENCY_ANALYST",
        "REPORT_REVIEWER",
        "REPOSITORY_ANALYST",
    ]
