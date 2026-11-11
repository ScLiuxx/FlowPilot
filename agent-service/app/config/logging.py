"""Structured JSON logging with correlation context.

Every log record carries the protocol identifiers (``traceId``,
``invocationId``, ``threadId``, ``messageId``) so that a single agent
invocation can be traced across the Java backend and this service.

The identifiers are kept in :mod:`contextvars` so that they are propagated
into the LangGraph worker tasks without having to thread them through every
function signature.
"""

from __future__ import annotations

import json
import logging
import sys
from contextlib import contextmanager
from contextvars import ContextVar
from datetime import datetime, timezone
from typing import Any, Iterator

_TRACE_ID: ContextVar[str | None] = ContextVar("trace_id", default=None)
_INVOCATION_ID: ContextVar[int | None] = ContextVar("invocation_id", default=None)
_THREAD_ID: ContextVar[str | None] = ContextVar("thread_id", default=None)
_MESSAGE_ID: ContextVar[str | None] = ContextVar("message_id", default=None)

_RESERVED = set(logging.LogRecord("", 0, "", 0, "", (), None).__dict__) | {
    "asctime",
    "message",
    "taskName",
}


class JsonFormatter(logging.Formatter):
    """Render log records as single line JSON documents."""

    def format(self, record: logging.LogRecord) -> str:
        payload: dict[str, Any] = {
            "timestamp": datetime.fromtimestamp(record.created, tz=timezone.utc)
            .isoformat(timespec="milliseconds")
            .replace("+00:00", "Z"),
            "level": record.levelname,
            "logger": record.name,
            "message": record.getMessage(),
        }
        for key, value in record.__dict__.items():
            if key not in _RESERVED and not key.startswith("_"):
                payload[key] = value
        # Protocol identifiers bound to the current execution context.
        for key, value in current_context().items():
            if value is not None:
                payload.setdefault(key, value)
        if record.exc_info:
            payload["exception"] = self.formatException(record.exc_info)
        return json.dumps(payload, default=str, ensure_ascii=False)


def configure_logging(level: str = "INFO", json_output: bool = True) -> None:
    """Install the root log handler. Safe to call more than once."""
    handler = logging.StreamHandler(stream=sys.stdout)
    if json_output:
        handler.setFormatter(JsonFormatter())
    else:
        handler.setFormatter(
            logging.Formatter("%(asctime)s %(levelname)s %(name)s %(message)s")
        )
    root = logging.getLogger()
    for existing in list(root.handlers):
        root.removeHandler(existing)
    root.addHandler(handler)
    root.setLevel(level.upper())


def get_logger(name: str) -> logging.Logger:
    return logging.getLogger(name)


def current_context() -> dict[str, Any]:
    return {
        "traceId": _TRACE_ID.get(),
        "invocationId": _INVOCATION_ID.get(),
        "threadId": _THREAD_ID.get(),
        "messageId": _MESSAGE_ID.get(),
    }


@contextmanager
def log_context(
    *,
    trace_id: str | None = None,
    invocation_id: int | None = None,
    thread_id: str | None = None,
    message_id: str | None = None,
) -> Iterator[None]:
    """Bind protocol identifiers to the current execution context."""
    tokens = []
    if trace_id is not None:
        tokens.append((_TRACE_ID, _TRACE_ID.set(trace_id)))
    if invocation_id is not None:
        tokens.append((_INVOCATION_ID, _INVOCATION_ID.set(invocation_id)))
    if thread_id is not None:
        tokens.append((_THREAD_ID, _THREAD_ID.set(thread_id)))
    if message_id is not None:
        tokens.append((_MESSAGE_ID, _MESSAGE_ID.set(message_id)))
    try:
        yield
    finally:
        for var, token in reversed(tokens):
            var.reset(token)
