"""The transactional outbox of the agent service.

The result event must be written in the *same* transaction as the execution
record, otherwise a crash between the two would lose the result of an
invocation (architecture design, chapter 8.4).
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
from typing import Any

import pytest

from app.messaging.events import AgentExecutePayload
from app.messaging.outbox_publisher import OutboxPublisher
from app.persistence.repository import (
    OUTBOX_PENDING,
    OUTBOX_PUBLISHED,
    OUTBOX_PUBLISHING,
    OutboxMessage,
    PostgresExecutionRecordStore,
)
from tests.conftest import make_execute_envelope


class FakePublisher:
    def __init__(self, error: Exception | None = None) -> None:
        self.published: list[tuple[Any, str]] = []
        self.error = error
        self.connected = True

    async def publish(self, envelope, routing_key: str):
        if self.error is not None:
            raise self.error
        self.published.append((envelope, routing_key))
        return envelope


async def test_the_result_event_is_persisted_in_the_outbox(executor, store):
    envelope = make_execute_envelope()
    outcome = await executor.execute(
        envelope, AgentExecutePayload.model_validate(envelope.payload)
    )

    rows = await store.list_outbox()
    assert len(rows) == 1
    row = rows[0]
    assert row.status == OUTBOX_PENDING
    assert row.message_type == "AgentCompleted"
    assert row.routing_key == "agent.completed"
    # the stored body is the complete envelope of the result event
    assert row.payload["payload"]["invocationId"] == 30001
    assert row.payload["traceId"] == envelope.traceId
    assert row.payload["causationId"] == envelope.messageId
    assert outcome.publish is False


async def test_the_outbox_row_and_the_record_are_written_together(executor, store):
    envelope = make_execute_envelope()
    await executor.execute(
        envelope, AgentExecutePayload.model_validate(envelope.payload)
    )

    record = await store.get(30001)
    rows = await store.list_outbox()
    assert record is not None and record.status == "SUCCEEDED"
    assert len(rows) == 1


async def test_a_failed_invocation_is_persisted_in_the_outbox(executor, store):
    envelope = make_execute_envelope(thread_id="flowpilot-99999")
    await executor.execute(
        envelope, AgentExecutePayload.model_validate(envelope.payload)
    )

    rows = await store.list_outbox()
    assert len(rows) == 1
    assert rows[0].message_type == "AgentFailed"
    assert rows[0].routing_key == "agent.failed"


async def test_the_publisher_delivers_the_row_and_marks_it_as_published(store, settings):
    executor_store = store
    await executor_store.create(
        invocation_id=30001, thread_id="flowpilot-30001", agent_type="REPOSITORY_ANALYST"
    )
    await executor_store.update_with_outcome(
        30001,
        status="SUCCEEDED",
        outbox=OutboxMessage(
            message_id="m-1",
            message_type="AgentCompleted",
            routing_key="agent.completed",
            payload={
                "messageId": "6f1c9a2e-4d3b-4a5f-9c8e-1b2d3e4f5a6b",
                "messageType": "AgentCompleted",
                "correlationId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
                "workflowExecutionId": 1,
                "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
                "timestamp": "2026-11-11T10:00:00.000Z",
                "schemaVersion": "1.0",
                "producer": "flowpilot-agent-service",
                "payload": {"invocationId": 30001},
            },
        ),
    )

    publisher = FakePublisher()
    outbox = OutboxPublisher(store=store, publisher=publisher, settings=settings)

    delivered = await outbox.publish_pending()

    assert delivered == 1
    assert publisher.published[0][1] == "agent.completed"
    rows = await store.list_outbox()
    assert rows[0].status == OUTBOX_PUBLISHED
    assert rows[0].published_at is not None


async def test_a_failed_delivery_is_retried_with_a_backoff(store, settings):
    await store.create(
        invocation_id=30001, thread_id="flowpilot-30001", agent_type="REPOSITORY_ANALYST"
    )
    await store.update_with_outcome(
        30001,
        status="SUCCEEDED",
        outbox=OutboxMessage(
            message_id="m-1",
            message_type="AgentCompleted",
            routing_key="agent.completed",
            payload={
                "messageId": "6f1c9a2e-4d3b-4a5f-9c8e-1b2d3e4f5a6b",
                "messageType": "AgentCompleted",
                "correlationId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
                "workflowExecutionId": 1,
                "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
                "timestamp": "2026-11-11T10:00:00.000Z",
                "schemaVersion": "1.0",
                "producer": "flowpilot-agent-service",
                "payload": {"invocationId": 30001},
            },
        ),
    )

    publisher = FakePublisher(error=RuntimeError("broker down"))
    outbox = OutboxPublisher(store=store, publisher=publisher, settings=settings)

    assert await outbox.publish_pending() == 1

    rows = await store.list_outbox()
    assert rows[0].status == OUTBOX_PENDING
    assert rows[0].retry_count == 1
    assert rows[0].next_retry_at is not None
    # the row is not claimed again before the backoff elapsed
    assert await outbox.publish_pending() == 0


async def test_the_rows_stay_pending_while_the_broker_is_unreachable(store, settings):
    await store.create(
        invocation_id=30001, thread_id="flowpilot-30001", agent_type="REPOSITORY_ANALYST"
    )
    await store.update_with_outcome(
        30001,
        status="SUCCEEDED",
        outbox=OutboxMessage(
            message_id="6f1c9a2e-4d3b-4a5f-9c8e-1b2d3e4f5a6b",
            message_type="AgentCompleted",
            routing_key="agent.completed",
            payload={"messageId": "6f1c9a2e-4d3b-4a5f-9c8e-1b2d3e4f5a6b"},
        ),
    )

    publisher = FakePublisher()
    publisher.connected = False
    outbox = OutboxPublisher(store=store, publisher=publisher, settings=settings)

    assert await outbox.publish_pending() == 0
    rows = await store.list_outbox()
    assert rows[0].status == OUTBOX_PENDING
    assert rows[0].retry_count == 0


async def test_a_row_whose_publisher_died_is_reclaimed(store, settings):
    await store.create(
        invocation_id=30001, thread_id="flowpilot-30001", agent_type="REPOSITORY_ANALYST"
    )
    await store.update_with_outcome(
        30001,
        status="SUCCEEDED",
        outbox=OutboxMessage(
            message_id="m-1",
            message_type="AgentCompleted",
            routing_key="agent.completed",
            payload={"messageId": "m-1"},
        ),
    )
    claimed = await store.claim_outbox_batch(limit=10)
    assert claimed and claimed[0].status == OUTBOX_PUBLISHING

    # the publisher crashed: the row stays in PUBLISHING forever
    reclaimed = await store.reclaim_stale_outbox(
        stale_before=datetime.now(tz=timezone.utc) + timedelta(minutes=10)
    )
    assert reclaimed == 1
    rows = await store.list_outbox()
    assert rows[0].status == OUTBOX_PENDING


async def test_the_outbox_publisher_reclaims_the_stale_rows(store, settings):
    await store.create(
        invocation_id=30001, thread_id="flowpilot-30001", agent_type="REPOSITORY_ANALYST"
    )
    await store.update_with_outcome(
        30001,
        status="SUCCEEDED",
        outbox=OutboxMessage(
            message_id="m-1",
            message_type="AgentCompleted",
            routing_key="agent.completed",
            payload={
                "messageId": "6f1c9a2e-4d3b-4a5f-9c8e-1b2d3e4f5a6b",
                "messageType": "AgentCompleted",
                "correlationId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
                "workflowExecutionId": 1,
                "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
                "timestamp": "2026-11-11T10:00:00.000Z",
                "schemaVersion": "1.0",
                "producer": "flowpilot-agent-service",
                "payload": {"invocationId": 30001},
            },
        ),
    )
    await store.claim_outbox_batch(limit=10)

    publisher = FakePublisher()
    outbox = OutboxPublisher(store=store, publisher=publisher, settings=settings)
    settings.outbox_publishing_timeout_seconds = 0

    delivered = await outbox.publish_pending()

    assert delivered == 1
    rows = await store.list_outbox()
    assert rows[0].status == OUTBOX_PUBLISHED


# ---------------------------------------------------------------------------
# The PostgreSQL implementation
# ---------------------------------------------------------------------------


class FakeCursor:
    def __init__(self, connection: "FakeConnection") -> None:
        self._connection = connection

    async def __aenter__(self) -> "FakeCursor":
        return self

    async def __aexit__(self, *exc_info: Any) -> bool:
        return False

    async def execute(self, sql: str, params: Any = None) -> None:
        self._connection.statements.append(sql)
        if "INSERT INTO agent_outbox" in sql and self._connection.fail_on_outbox:
            raise RuntimeError("the outbox insert failed")

    async def fetchone(self) -> Any:
        return self._connection.row

    async def fetchall(self) -> list[Any]:
        return []


class FakeConnection:
    def __init__(self, *, row: Any = None, fail_on_outbox: bool = False) -> None:
        self.row = row
        self.fail_on_outbox = fail_on_outbox
        self.statements: list[str] = []
        self.commits = 0
        self.rollbacks = 0

    def cursor(self) -> FakeCursor:
        return FakeCursor(self)

    async def commit(self) -> None:
        self.commits += 1

    async def rollback(self) -> None:
        self.rollbacks += 1


class FakePoolContext:
    def __init__(self, connection: FakeConnection) -> None:
        self._connection = connection

    async def __aenter__(self) -> FakeConnection:
        return self._connection

    async def __aexit__(self, *exc_info: Any) -> bool:
        return False


class FakePool:
    def __init__(self, connection: FakeConnection) -> None:
        self._connection = connection

    def connection(self) -> FakePoolContext:
        return FakePoolContext(self._connection)


def _postgres_store(connection: FakeConnection) -> PostgresExecutionRecordStore:
    store = PostgresExecutionRecordStore("postgresql://unused")
    store._pool = FakePool(connection)
    return store


async def test_the_postgres_store_commits_the_record_and_the_outbox_together():
    row = (
        30001,
        "flowpilot-30001",
        "REPOSITORY_ANALYST",
        "SUCCEEDED",
        None,
        "checkpoint-1",
        None,
        None,
        None,
        None,
        None,
        None,
        None,
    )
    connection = FakeConnection(row=row)
    store = _postgres_store(connection)

    await store.update_with_outcome(
        30001,
        status="SUCCEEDED",
        outbox=OutboxMessage(
            message_id="m-1",
            message_type="AgentCompleted",
            routing_key="agent.completed",
            payload={"messageId": "m-1"},
        ),
    )

    assert connection.commits == 1
    assert connection.rollbacks == 0
    statements = " ".join(connection.statements)
    assert "UPDATE agent_execution_record" in statements
    assert "INSERT INTO agent_outbox" in statements


async def test_the_postgres_store_rolls_back_when_the_outbox_insert_fails():
    connection = FakeConnection(row=None, fail_on_outbox=True)
    store = _postgres_store(connection)

    with pytest.raises(RuntimeError):
        await store.update_with_outcome(
            30001,
            status="SUCCEEDED",
            outbox=OutboxMessage(
                message_id="m-1",
                message_type="AgentCompleted",
                routing_key="agent.completed",
                payload={"messageId": "m-1"},
            ),
        )

    # the update of the record must not survive the failed outbox insert
    assert connection.commits == 0
    assert connection.rollbacks == 1
