"""LangGraph checkpointers.

Production uses the PostgreSQL checkpointer of ``langgraph-checkpoint-postgres``
against the ``flowpilot_agent`` database. The tests use the in memory saver
(``langgraph.checkpoint.memory``) so that no infrastructure is required.

The ``thread_id`` is always the one the Java backend sends — ``flowpilot-{invocationId}``.
It is never regenerated, not even when a message is redelivered, so a resumed
agent always continues on the very same thread.
"""

from __future__ import annotations

from contextlib import asynccontextmanager
from typing import Any, AsyncIterator


def expected_thread_id(invocation_id: int) -> str:
    """The canonical LangGraph thread id of an invocation."""
    return f"flowpilot-{invocation_id}"


@asynccontextmanager
async def postgres_checkpointer(dsn: str) -> AsyncIterator[Any]:
    """Open a PostgreSQL checkpointer and make sure the schema exists."""
    from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver

    async with AsyncPostgresSaver.from_conn_string(dsn) as saver:
        await saver.setup()
        yield saver


def memory_checkpointer() -> Any:
    """In memory checkpointer used by the tests and the offline demo."""
    from langgraph.checkpoint.memory import InMemorySaver

    return InMemorySaver()


async def latest_checkpoint_id(checkpointer: Any, thread_id: str) -> str | None:
    """Return the id of the newest checkpoint of ``thread_id``."""
    config = {"configurable": {"thread_id": thread_id}}
    getter = getattr(checkpointer, "aget_tuple", None)
    if getter is not None:
        tuple_ = await getter(config)
    else:  # pragma: no cover - synchronous saver
        tuple_ = checkpointer.get_tuple(config)
    if tuple_ is None:
        return None
    config = tuple_.config or {}
    return (config.get("configurable") or {}).get("checkpoint_id")


async def list_checkpoints(checkpointer: Any, thread_id: str, limit: int = 20) -> list[dict[str, Any]]:
    """Return a short summary of the checkpoints of ``thread_id``."""
    config = {"configurable": {"thread_id": thread_id}}
    entries: list[dict[str, Any]] = []
    async for checkpoint in checkpointer.alist(config):
        entries.append(
            {
                "checkpointId": (checkpoint.config or {})
                .get("configurable", {})
                .get("checkpoint_id"),
                "checkpointNs": (checkpoint.config or {}).get("configurable", {}).get("checkpoint_ns"),
                "step": (checkpoint.metadata or {}).get("step"),
                "source": (checkpoint.metadata or {}).get("source"),
            }
        )
        if len(entries) >= limit:
            break
    return entries
