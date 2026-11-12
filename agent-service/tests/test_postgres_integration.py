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
from datetime import datetime, timedelta, timezone

import pytest

from langgraph.graph import END, START, StateGraph

from app.persistence.checkpoint import latest_checkpoint_id, postgres_checkpointer
from app.persistence.repository import (
    RESUME_CLAIMED,
    RESUME_RECOVERABLE,
    STATUS_CANCELLED,
    STATUS_INTERRUPTED,
    STATUS_RUNNING,
    STATUS_SUCCEEDED,
    GuardRejected,
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


async def test_the_resume_lease_is_taken_over_after_the_worker_died():
    """The takeover of a resume whose worker died works against PostgreSQL.

    The lease is what makes the resume recoverable: the worker that died left
    an expired lease behind, and the next delivery takes it over.
    """
    await apply_schema(DSN)

    invocation_id = 95_000_000 + (uuid.uuid4().int % 4_000_000)
    store = PostgresExecutionRecordStore(DSN)
    await store.connect()
    try:
        await store.create(
            invocation_id=invocation_id,
            thread_id=f"flowpilot-{invocation_id}",
            agent_type="REPORT_REVIEWER",
        )

        # The worker claimed the resume and died right after.
        expired = datetime.now(tz=timezone.utc) - timedelta(seconds=5)
        record, created = await store.create_resume(
            invocation_id=invocation_id,
            resume_request_id="resume-001",
            thread_id=f"flowpilot-{invocation_id}",
            status=RESUME_CLAIMED,
            payload={},
            lease_expire_at=expired,
        )
        assert created is True
        assert record.lease_token == 1

        # The next delivery takes the expired lease over.
        claimed = await store.claim_resume_lease(
            invocation_id,
            "resume-001",
            lease_expire_at=datetime.now(tz=timezone.utc) + timedelta(seconds=60),
            expected_lease_token=record.lease_token,
        )
        assert claimed is not None, "the expired lease must be taken over"
        assert claimed.lease_token == record.lease_token + 1
        assert claimed.takeover_count == 1

        # A second worker with the stale token must not win.
        second = await store.claim_resume_lease(
            invocation_id,
            "resume-001",
            lease_expire_at=datetime.now(tz=timezone.utc) + timedelta(seconds=60),
            expected_lease_token=record.lease_token,
        )
        assert second is None

        # The worker that lost the lease must not close the ledger.
        refused = await store.complete_resume(
            invocation_id,
            "resume-001",
            lease_token=record.lease_token,
            status="COMPLETED",
            payload={"messageType": "AgentCompleted"},
        )
        assert refused is None

        # The new owner closes the ledger.
        closed = await store.complete_resume(
            invocation_id,
            "resume-001",
            lease_token=claimed.lease_token,
            status="COMPLETED",
            payload={"messageType": "AgentCompleted"},
            checkpoint_id="checkpoint-1",
        )
        assert closed is not None
        assert closed.status == "COMPLETED"
        assert closed.lease_expire_at is None
    finally:
        await store.close()


async def test_a_recoverable_resume_can_be_taken_over():
    """A resume that was given up can be continued again.

    The ledger of a resume that was taken over too often says ``RECOVERABLE``.
    The request may be taken over from that state, so the thread and its
    checkpoint are not lost.
    """
    await apply_schema(DSN)

    invocation_id = 97_000_000 + (uuid.uuid4().int % 4_000_000)
    store = PostgresExecutionRecordStore(DSN)
    await store.connect()
    try:
        record, created = await store.create_resume(
            invocation_id=invocation_id,
            resume_request_id="resume-001",
            thread_id=f"flowpilot-{invocation_id}",
            status=RESUME_CLAIMED,
            payload={},
            lease_expire_at=datetime.now(tz=timezone.utc) - timedelta(seconds=1),
        )
        assert created is True

        # The worker gives up and marks the request as recoverable. The write
        # is fenced by the lease token.
        marked = await store.update_resume(
            invocation_id,
            "resume-001",
            status=RESUME_RECOVERABLE,
            lease_token=record.lease_token,
        )
        assert marked is not None
        assert marked.status == RESUME_RECOVERABLE

        # A worker with the wrong token must not be able to change it.
        refused = await store.update_resume(
            invocation_id,
            "resume-001",
            status=RESUME_CLAIMED,
            lease_token=record.lease_token + 5,
        )
        assert refused is None

        # The request may be taken over from the recoverable state.
        claimed = await store.claim_resume_lease(
            invocation_id,
            "resume-001",
            lease_expire_at=datetime.now(tz=timezone.utc) + timedelta(seconds=60),
            expected_lease_token=record.lease_token,
        )
        assert claimed is not None, "a recoverable resume must be claimable"
        assert claimed.status == RESUME_CLAIMED
        assert claimed.takeover_count == 1
    finally:
        await store.close()


async def test_the_outcome_is_not_written_after_a_cancel():
    """A cancelled invocation never receives a late success event."""
    await apply_schema(DSN)

    invocation_id = 96_000_000 + (uuid.uuid4().int % 4_000_000)
    store = PostgresExecutionRecordStore(DSN)
    await store.connect()
    try:
        await store.create(
            invocation_id=invocation_id,
            thread_id=f"flowpilot-{invocation_id}",
            agent_type="REPOSITORY_ANALYST",
        )
        # The invocation was cancelled.
        await store.update(invocation_id, status=STATUS_CANCELLED)

        with pytest.raises(GuardRejected):
            await store.update_with_outcome(
                invocation_id,
                status=STATUS_SUCCEEDED,
                result={"invocationId": invocation_id},
                guard_statuses=(STATUS_RUNNING, STATUS_INTERRUPTED),
            )

        # The record was not overwritten.
        record = await store.get(invocation_id)
        assert record is not None
        assert record.status == STATUS_CANCELLED
    finally:
        await store.close()
