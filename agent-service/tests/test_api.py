"""The HTTP surface: health, readiness and the internal debug endpoints."""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app.config.settings import Settings
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


def test_the_service_refuses_to_start_without_a_token_in_production(settings):
    """A deployment that exposes the invocation records must not start."""
    settings.environment = "production"
    settings.internal_api_token = None
    with pytest.raises(ValueError, match="INTERNAL_API_TOKEN"):
        create_app(settings=settings, start_messaging=False, start_persistence=False)


def test_the_service_starts_without_a_token_in_development(settings):
    settings.environment = "local"
    settings.internal_api_token = None
    with _client(settings) as client:
        assert client.get("/internal/agents").status_code == 200


def test_the_internal_api_is_closed_without_a_token_in_production(settings, monkeypatch):
    """Even if the service runs, the endpoints must not answer."""
    settings.environment = "production"
    settings.internal_api_token = None
    # The startup validation is bypassed, the endpoints still refuse.
    monkeypatch.setattr(Settings, "validate", lambda self: None)
    with _client(settings) as client:
        assert client.get("/internal/agents").status_code == 503


def test_the_registry_contains_the_default_graphs():
    registry = register_default_graphs(AgentGraphRegistry())
    assert registry.agent_types() == [
        "DEPENDENCY_ANALYST",
        "LLM",
        "REPORT_REVIEWER",
        "REPOSITORY_ANALYST",
    ]
