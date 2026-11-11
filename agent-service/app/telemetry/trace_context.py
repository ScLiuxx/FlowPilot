"""Building the parent span context out of a protocol envelope.

The Java backend opens the trace and passes its id in the envelope. The
``traceId`` is a 32 character lower case hex string, i.e. exactly the wire
format of an OpenTelemetry trace id.

The ``spanId`` of the envelope is the span of the producer, so the spans of
this service become its children and the whole chain of one execution ends up
in a single trace. A message from a producer that does not know about spans
carries no span id, so the id is derived from the ``messageId`` instead: the
same command then always produces the same parent span, which keeps the trace
stable across redeliveries and across replicas.
"""

from __future__ import annotations

import hashlib

from opentelemetry import trace
from opentelemetry.trace import NonRecordingSpan, SpanContext, TraceFlags, TraceState

#: The span id of a remote parent is 16 hex characters (8 bytes).
_SPAN_ID_LENGTH = 16


def span_id_from_message_id(message_id: str) -> int:
    """Derive a stable span id from a message id."""
    digest = hashlib.sha256((message_id or "").encode("utf-8")).hexdigest()
    return int(digest[:_SPAN_ID_LENGTH], 16)


def parent_context(
    trace_id: str, message_id: str, span_id: str | None = None
) -> trace.Context:
    """Build the parent context of the spans created for one message."""
    try:
        trace_id_value = int(trace_id, 16)
    except (TypeError, ValueError):
        # A malformed trace id must not break the execution: the spans are
        # then started as new roots.
        return trace.get_current_context()
    parent_span_id = _resolve_span_id(span_id, message_id)
    context = SpanContext(
        trace_id=trace_id_value,
        span_id=parent_span_id,
        is_remote=True,
        trace_flags=TraceFlags(TraceFlags.SAMPLED),
        trace_state=TraceState(),
    )
    return trace.set_span_in_context(NonRecordingSpan(context))


def _resolve_span_id(span_id: str | None, message_id: str) -> int:
    """The span id of the producer, or the derived one."""
    if span_id:
        try:
            return int(span_id, 16)
        except (TypeError, ValueError):
            pass
    return span_id_from_message_id(message_id)
