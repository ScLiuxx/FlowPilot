"""Health and readiness endpoints."""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

from fastapi import APIRouter, Request, Response

from app.config.settings import Settings, get_settings

router = APIRouter(tags=["health"])


@router.get("/health")
async def health(request: Request) -> dict[str, Any]:
    """Liveness probe — always answers while the process is alive."""
    settings: Settings = getattr(request.app.state, "settings", None) or get_settings()
    return {
        "status": "UP",
        "service": settings.service_name,
        "version": settings.version,
        "environment": settings.environment,
        "timestamp": datetime.now(tz=timezone.utc)
        .isoformat(timespec="milliseconds")
        .replace("+00:00", "Z"),
    }


@router.get("/ready")
async def ready(request: Request, response: Response) -> dict[str, Any]:
    """Readiness probe — verifies the broker and the database."""
    settings: Settings = getattr(request.app.state, "settings", None) or get_settings()
    checks: dict[str, Any] = {}

    store = getattr(request.app.state, "store", None)
    degraded = bool(getattr(request.app.state, "persistence_degraded", False))
    if settings.persistence_enabled and store is not None:
        if degraded:
            # The service runs on the in memory fallback: the persistence is
            # *not* available, the probe must not report it as healthy.
            checks["postgres"] = "DEGRADED: in-memory fallback"
        else:
            try:
                await store.list_records(limit=1)
                checks["postgres"] = "UP"
            except Exception as exc:
                checks["postgres"] = f"DOWN: {type(exc).__name__}"
    else:
        checks["postgres"] = "DISABLED"

    publisher = getattr(request.app.state, "publisher", None)
    consumer = getattr(request.app.state, "consumer", None)
    if settings.messaging_enabled:
        broker_up = bool(
            (publisher is not None and publisher.connected)
            or (consumer is not None and consumer.connected)
        )
        checks["rabbitmq"] = "UP" if broker_up else "DOWN"
    else:
        checks["rabbitmq"] = "DISABLED"

    healthy = all(value in ("UP", "DISABLED") for value in checks.values())
    if not healthy:
        response.status_code = 503
    return {
        "status": "UP" if healthy else "DOWN",
        "service": settings.service_name,
        "checks": checks,
        "timestamp": datetime.now(tz=timezone.utc)
        .isoformat(timespec="milliseconds")
        .replace("+00:00", "Z"),
    }
