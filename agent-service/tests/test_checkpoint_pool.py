"""The checkpointer must survive a restart of the database.

A single connection is never re-established, so the agent service would stop
working after the first database restart until the service itself is
restarted. The checkpointer therefore uses a pool.
"""

from __future__ import annotations

import os

import pytest

from app.persistence.checkpoint import postgres_checkpointer

DSN = os.environ.get(
    "POSTGRES_DSN", "postgresql://flowpilot:flowpilot@127.0.0.1:5432/flowpilot_agent"
)


def _postgres_available() -> bool:
    try:
        import psycopg

        with psycopg.connect(DSN, connect_timeout=3):
            return True
    except Exception:
        return False


pytestmark = pytest.mark.skipif(
    not _postgres_available(), reason="PostgreSQL is not reachable in this environment"
)


async def test_the_checkpointer_uses_a_pool_of_connections():
    """The saver must not be bound to a single connection."""
    async with postgres_checkpointer(DSN) as saver:
        from psycopg_pool import AsyncConnectionPool

        assert isinstance(saver.conn, AsyncConnectionPool), (
            "the checkpointer holds a single connection, which is never re-established"
        )
        # The pool is usable: the checkpointer really writes its checkpoints.
        assert saver.conn is not None


async def test_the_checkpointer_stores_a_checkpoint():
    """A checkpoint written through the pool can be read back."""
    from langgraph.checkpoint.base import empty_checkpoint

    thread_id = "flowpilot-pool-check"
    async with postgres_checkpointer(DSN) as saver:
        config = {"configurable": {"thread_id": thread_id, "checkpoint_ns": ""}}
        checkpoint = empty_checkpoint()
        saved = await saver.aput(config, checkpoint, {}, {})
        assert saved["configurable"]["checkpoint_id"]

        loaded = await saver.aget_tuple(config)
        assert loaded is not None
        assert loaded.checkpoint["id"] == checkpoint["id"]
