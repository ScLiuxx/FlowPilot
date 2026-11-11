"""The startup behaviour when PostgreSQL is not reachable.

A degraded agent service loses its checkpoints and its idempotency guarantee:
the operator has to decide explicitly whether the service may start anyway.
"""

from __future__ import annotations

from contextlib import asynccontextmanager
from typing import Any, AsyncIterator

import pytest
from fastapi.testclient import TestClient

from app.main import create_app
from app.persistence.repository import PostgresExecutionRecordStore


@pytest.fixture
def broken_postgres(monkeypatch):
    """Make every attempt to reach PostgreSQL fail."""

    async def failing_connect(self) -> None:
        raise RuntimeError("the postgres server is unreachable")

    @asynccontextmanager
    async def failing_checkpointer(dsn: str) -> AsyncIterator[Any]:
        raise RuntimeError("the postgres server is unreachable")
        yield  # pragma: no cover - the generator never yields

    monkeypatch.setattr(PostgresExecutionRecordStore, "connect", failing_connect)
    monkeypatch.setattr("app.main.postgres_checkpointer", failing_checkpointer)


def test_the_service_refuses_to_start_without_postgres(settings, broken_postgres):
    settings.persistence_enabled = True
    settings.persistence_required = True
    app = create_app(settings=settings, start_messaging=False)

    with pytest.raises(RuntimeError):
        with TestClient(app):
            pass


def test_the_service_starts_in_the_degraded_mode_when_it_is_allowed(
    settings, broken_postgres
):
    settings.persistence_enabled = True
    settings.persistence_required = False
    app = create_app(settings=settings, start_messaging=False)

    with TestClient(app) as client:
        response = client.get("/ready")

    # the degraded mode must not be reported as healthy
    assert response.status_code == 503
    body = response.json()
    assert body["status"] == "DOWN"
    assert body["checks"]["postgres"].startswith("DEGRADED")
