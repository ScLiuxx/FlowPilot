"""OpenTelemetry instrumentation of the agent runtime.

The agent service is one hop of a distributed trace that starts in the Java
backend. The ``traceId`` of the message envelope is a 32 character hex string,
which is exactly the OpenTelemetry trace id format, so the Python spans can
join the trace the Java side already opened (see the protocol design,
chapter 2.2).

The package exposes three things:

* :func:`configure_tracing` — installs the tracer provider (and, when an OTLP
  endpoint is configured, the gRPC exporter).
* :func:`get_tracer` — the global tracer used by the runtime.
* :func:`parent_context` — builds the parent context of a span out of the
  ``traceId`` / ``messageId`` of an envelope.
"""

from __future__ import annotations

from app.telemetry.trace_context import parent_context, span_id_from_message_id
from app.telemetry.tracing import (
    configure_tracing,
    flush_tracing,
    get_tracer,
)

__all__ = [
    "configure_tracing",
    "flush_tracing",
    "get_tracer",
    "parent_context",
    "span_id_from_message_id",
]
