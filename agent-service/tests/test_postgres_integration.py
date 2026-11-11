"""Optional integration test against the real PostgreSQL.

The test suite must pass **without** PostgreSQL, therefore every test in this
module is skipped when the database is not reachable. When it is reachable the
test proves that

* the DDL of ``app/persistence/schema.sql`` is valid,
* the unique constraints of the tables implement the protocol idempotency
  rules, and
* the LangGraph PostgreSQL checkpointer really stores the checkpoints of the
  thread ``flowpilot-{invocationId}``.
"""

from __future__ import annotations

import os
import uuid

import pytest

from langgraph.graph import END, START, StateGraph

from app.persistence.checkpoint import latest_checkpoint_id, postgres_checkpointer
from app.persistence.repository import (
    STATUS_SUCCEEDED,
    PostgresExecutionRecordStore,
    apply_schema,
)
from app.runtime.state import AgentState

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


async def test_the_schema_can_be_applied_and_the_store_is_idempotent():
    await apply_schema(DSN)

    # a unique invocation id per run keeps the test re-runnable
    invocation_id = 90_000_000 + (uuid.uuid4().int % 9_000_000)

    store = PostgresExecutionRecordStore(DSN)
    await store.connect()
    try:
        record, created = await store.create(
            invocation_id=invocation_id,
            thread_id=f"flowpilot-{invocation_id}",
            agent_type="REPOSITORY_ANALYST",
        )
        assert created is True
        assert record.status == "RUNNING"

        again, created_again = await store.create(
            invocation_id=invocation_id,
            thread_id=f"flowpilot-{invocation_id}",
            agent_type="REPOSITORY_ANALYST",
        )
        assert created_again is False, "the invocation id must be unique"
        assert again.invocation_id == invocation_id

        updated = await store.update(
            invocation_id,
            status=STATUS_SUCCEEDED,
            checkpoint_id="checkpoint-1",
            result={"invocationId": invocation_id},
        )
        assert updated is not None and updated.status == STATUS_SUCCEEDED

        resume, resume_created = await store.create_resume(
            invocation_id=invocation_id,
            resume_request_id="resume-001",
            thread_id=f"flowpilot-{invocation_id}",
            status="COMPLETED",
            payload={"messageType": "AgentCompleted"},
        )
        assert resume_created is True
        _, resume_created_again = await store.create_resume(
            invocation_id=invocation_id,
            resume_request_id="resume-001",
            thread_id=f"flowpilot-{invocation_id}",
            status="COMPLETED",
            payload={"messageType": "AgentCompleted"},
        )
        assert resume_created_again is False, "the resume request id must be unique"

        fetched = await store.get(invocation_id)
        assert fetched is not None
        assert fetched.checkpoint_id == "checkpoint-1"
    finally:
        await store.close()


async def test_the_postgres_checkpointer_stores_the_thread_checkpoints():
    await apply_schema(DSN)

    async def node(state: AgentState) -> dict:
        return {"iterations": 1}

    graph = StateGraph(AgentState)
    graph.add_node("node", node)
    graph.add_edge(START, "node")
    graph.add_edge("node", END)

    thread_id = "flowpilot-99000002"
    async with postgres_checkpointer(DSN) as saver:
        compiled = graph.compile(checkpointer=saver)
        await compiled.ainvoke({"iterations": 0}, {"configurable": {"thread_id": thread_id}})
        checkpoint_id = await latest_checkpoint_id(saver, thread_id)

    assert checkpoint_id, "the checkpoint must be persisted in PostgreSQL"
