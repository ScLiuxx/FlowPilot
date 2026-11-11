"""Tracer provider setup and the global tracer accessor.

The provider is installed once per process. When no OTLP endpoint is
configured the spans are still created (so that the instrumentation code does
not need to care about the configuration) but nothing is exported.

Span attributes only carry identifiers, counters and error codes: API keys,
prompts and repository content must never end up in a trace (architecture
design, chapter 12).
"""

from __future__ import annotations

from typing import Any

from opentelemetry import trace
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor

from app.config.logging import get_logger
from app.config.settings import Settings, get_settings

logger = get_logger(__name__)

TRACER_NAME = "flowpilot.agent"

_provider: TracerProvider | None = None


def configure_tracing(settings: Settings | None = None) -> TracerProvider | None:
    """Install the tracer provider. Returns ``None`` when tracing is disabled."""
    global _provider
    settings = settings or get_settings()
    if not settings.otel_enabled:
        logger.info("opentelemetry is disabled")
        return None
    if _provider is not None:
        return _provider

    resource = Resource.create(
        {
            "service.name": settings.otel_service_name,
            "service.version": settings.version,
            "deployment.environment": settings.environment,
        }
    )
    provider = TracerProvider(resource=resource)
    endpoint = (settings.otel_exporter_otlp_endpoint or "").strip()
    if endpoint:
        from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import (
            OTLPSpanExporter,
        )

        provider.add_span_processor(
            BatchSpanProcessor(OTLPSpanExporter(endpoint=endpoint, insecure=True))
        )
        logger.info("opentelemetry exporter configured", extra={"endpoint": endpoint})
    else:
        logger.info(
            "opentelemetry is enabled without an exporter, spans stay local"
        )
    trace.set_tracer_provider(provider)
    _provider = provider
    return provider


def get_tracer(name: str = TRACER_NAME) -> Any:
    """The tracer of the agent runtime."""
    return trace.get_tracer(name)


def flush_tracing() -> None:
    """Flush the pending spans (called on shutdown).

    The provider itself is stopped by the atexit hook of the SDK: flushing
    keeps it usable, so that a restarted application still reports its spans.
    """
    if _provider is None:
        return
    try:
        _provider.force_flush()
    except Exception:  # pragma: no cover - best effort shutdown
        logger.exception("failed to flush the tracer provider")
