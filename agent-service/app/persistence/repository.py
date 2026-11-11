"""Durable execution records — the backbone of the idempotency guarantee.

Two stores implement the same protocol:

* :class:`PostgresExecutionRecordStore` — production, backed by the
  ``agent_execution_record`` / ``agent_resume_record`` / ``agent_outbox``
  tables (see ``schema.sql``).
* :class:`InMemoryExecutionRecordStore` — used by the tests and by the local
  demo when PostgreSQL is not available.

The unique constraints of the tables implement the protocol idempotency rules:

* ``AgentExecute``  -> ``invocation_id``
* ``AgentResume``   -> ``invocation_id`` + ``resume_request_id``

A redelivered message therefore never starts a second agent run: the store
reports that the record already exists and the executor returns the recorded
terminal result.

The result events are written to a transactional outbox: the update of the
execution record and the insertion of the outbox row happen in **one**
transaction, so a result event can never be lost (architecture design,
chapter 8.4).
"""

from __future__ import annotations

import asyncio
import json
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Protocol, runtime_checkable

# Terminal states — a redelivered message must not start a new run.
STATUS_RUNNING = "RUNNING"
STATUS_SUCCEEDED = "SUCCEEDED"
STATUS_FAILED = "FAILED"
STATUS_INTERRUPTED = "INTERRUPTED"
STATUS_CANCELLED = "CANCELLED"

#: A resume request that has been claimed but is not finished yet.
RESUME_PROCESSING = "PROCESSING"

#: Lifecycle of an outbox row.
OUTBOX_PENDING = "PENDING"
OUTBOX_PUBLISHING = "PUBLISHING"
OUTBOX_PUBLISHED = "PUBLISHED"

TERMINAL_STATUSES = frozenset(
    {STATUS_SUCCEEDED, STATUS_FAILED, STATUS_INTERRUPTED, STATUS_CANCELLED}
)


@dataclass
class ExecutionRecord:
    """Durable state of one agent invocation."""

    invocation_id: int
    thread_id: str
    agent_type: str
    status: str
    attempt_no: int | None = None
    checkpoint_id: str | None = None
    result: dict[str, Any] | None = None
    error: dict[str, Any] | None = None
    usage: dict[str, Any] | None = None
    resume_request_id: str | None = None
    message_id: str | None = None
    created_at: datetime | None = None
    updated_at: datetime | None = None

    @property
    def is_terminal(self) -> bool:
        return self.status in TERMINAL_STATUSES


@dataclass
class ResumeRecord:
    """Idempotency ledger entry of a processed ``AgentResume``."""

    invocation_id: int
    resume_request_id: str
    thread_id: str
    status: str
    payload: dict[str, Any] = field(default_factory=dict)
    checkpoint_id: str | None = None
    created_at: datetime | None = None


@dataclass(frozen=True)
class OutboxMessage:
    """A result event that has to be delivered to RabbitMQ.

    ``payload`` carries the complete outgoing envelope: the outbox row is
    written before the event is published, therefore the envelope has to be
    stored as it is — the trace context of the command cannot be derived
    again once the transaction is committed.
    """

    message_id: str
    message_type: str
    routing_key: str
    payload: dict[str, Any]


@dataclass
class OutboxRecord:
    """A persisted outbox row."""

    id: int
    message_id: str
    message_type: str
    routing_key: str
    payload: dict[str, Any]
    status: str = OUTBOX_PENDING
    retry_count: int = 0
    next_retry_at: datetime | None = None
    created_at: datetime | None = None
    published_at: datetime | None = None


@runtime_checkable
class ExecutionRecordStore(Protocol):
    """Persistence contract used by the executor."""

    async def get(self, invocation_id: int) -> ExecutionRecord | None: ...

    async def create(
        self,
        *,
        invocation_id: int,
        thread_id: str,
        agent_type: str,
        status: str = STATUS_RUNNING,
        attempt_no: int | None = None,
        message_id: str | None = None,
    ) -> tuple[ExecutionRecord, bool]:
        """Insert the invocation record. Returns ``(record, created)``."""

    async def update(
        self,
        invocation_id: int,
        *,
        status: str | None = None,
        attempt_no: int | None = None,
        checkpoint_id: str | None = None,
        result: dict[str, Any] | None = None,
        error: dict[str, Any] | None = None,
        usage: dict[str, Any] | None = None,
        resume_request_id: str | None = None,
    ) -> ExecutionRecord | None: ...

    async def update_with_outcome(
        self,
        invocation_id: int,
        *,
        status: str,
        checkpoint_id: str | None = None,
        result: dict[str, Any] | None = None,
        error: dict[str, Any] | None = None,
        usage: dict[str, Any] | None = None,
        outbox: OutboxMessage | None = None,
    ) -> ExecutionRecord | None:
        """Update the record and enqueue the result event in one transaction."""

    async def claim_outbox_batch(self, *, limit: int = 20) -> list[OutboxRecord]:
        """Claim pending outbox rows for delivery."""

    async def mark_outbox_published(self, outbox_id: int) -> None:
        """Mark an outbox row as delivered."""

    async def mark_outbox_failed(
        self, outbox_id: int, *, retry_count: int, next_retry_at: datetime
    ) -> None:
        """Return an outbox row to PENDING and schedule the next attempt."""

    async def reclaim_stale_outbox(self, *, stale_before: datetime) -> int:
        """Return rows that stayed in PUBLISHING for too long to PENDING."""

    async def list_outbox(self, limit: int = 50) -> list[OutboxRecord]:
        """The most recent outbox rows (debugging / tests)."""

    async def get_resume(
        self, invocation_id: int, resume_request_id: str
    ) -> ResumeRecord | None: ...

    async def create_resume(
        self,
        *,
        invocation_id: int,
        resume_request_id: str,
        thread_id: str,
        status: str,
        payload: dict[str, Any] | None = None,
        checkpoint_id: str | None = None,
    ) -> tuple[ResumeRecord, bool]: ...

    async def update_resume(
        self,
        invocation_id: int,
        resume_request_id: str,
        *,
        status: str,
        payload: dict[str, Any] | None = None,
        checkpoint_id: str | None = None,
    ) -> ResumeRecord | None: ...

    async def list_records(self, limit: int = 50) -> list[ExecutionRecord]: ...

    async def close(self) -> None: ...


# ---------------------------------------------------------------------------
# In memory implementation (tests / local demo)
# ---------------------------------------------------------------------------


class InMemoryExecutionRecordStore:
    """Non durable store with the exact semantics of the SQL implementation."""

    def __init__(self) -> None:
        self._records: dict[int, ExecutionRecord] = {}
        self._resumes: dict[tuple[int, str], ResumeRecord] = {}
        self._outbox: dict[int, OutboxRecord] = {}
        self._next_outbox_id = 1
        self._lock = asyncio.Lock()

    async def get(self, invocation_id: int) -> ExecutionRecord | None:
        async with self._lock:
            record = self._records.get(invocation_id)
            return _copy_record(record) if record else None

    async def create(
        self,
        *,
        invocation_id: int,
        thread_id: str,
        agent_type: str,
        status: str = STATUS_RUNNING,
        attempt_no: int | None = None,
        message_id: str | None = None,
    ) -> tuple[ExecutionRecord, bool]:
        async with self._lock:
            existing = self._records.get(invocation_id)
            if existing is not None:
                return _copy_record(existing), False
            now = datetime.now(tz=timezone.utc)
            record = ExecutionRecord(
                invocation_id=invocation_id,
                thread_id=thread_id,
                agent_type=agent_type,
                status=status,
                attempt_no=attempt_no,
                message_id=message_id,
                created_at=now,
                updated_at=now,
            )
            self._records[invocation_id] = record
            return _copy_record(record), True

    async def update(
        self,
        invocation_id: int,
        *,
        status: str | None = None,
        attempt_no: int | None = None,
        checkpoint_id: str | None = None,
        result: dict[str, Any] | None = None,
        error: dict[str, Any] | None = None,
        usage: dict[str, Any] | None = None,
        resume_request_id: str | None = None,
    ) -> ExecutionRecord | None:
        async with self._lock:
            record = self._records.get(invocation_id)
            if record is None:
                return None
            if status is not None:
                record.status = status
            if attempt_no is not None:
                record.attempt_no = attempt_no
            if checkpoint_id is not None:
                record.checkpoint_id = checkpoint_id
            if result is not None:
                record.result = result
            if error is not None:
                record.error = error
            if usage is not None:
                record.usage = usage
            if resume_request_id is not None:
                record.resume_request_id = resume_request_id
            record.updated_at = datetime.now(tz=timezone.utc)
            return _copy_record(record)

    async def update_with_outcome(
        self,
        invocation_id: int,
        *,
        status: str,
        checkpoint_id: str | None = None,
        result: dict[str, Any] | None = None,
        error: dict[str, Any] | None = None,
        usage: dict[str, Any] | None = None,
        outbox: OutboxMessage | None = None,
    ) -> ExecutionRecord | None:
        """Update the record and enqueue the result event atomically."""
        async with self._lock:
            record = self._records.get(invocation_id)
            if outbox is not None:
                self._append_outbox(outbox)
            if record is None:
                return None
            record.status = status
            if checkpoint_id is not None:
                record.checkpoint_id = checkpoint_id
            if result is not None:
                record.result = dict(result)
            if error is not None:
                record.error = dict(error)
            if usage is not None:
                record.usage = dict(usage)
            record.updated_at = datetime.now(tz=timezone.utc)
            return _copy_record(record)

    def _append_outbox(self, message: OutboxMessage) -> OutboxRecord:
        record = OutboxRecord(
            id=self._next_outbox_id,
            message_id=message.message_id,
            message_type=message.message_type,
            routing_key=message.routing_key,
            payload=dict(message.payload),
            status=OUTBOX_PENDING,
            retry_count=0,
            next_retry_at=None,
            created_at=datetime.now(tz=timezone.utc),
        )
        self._next_outbox_id += 1
        self._outbox[record.id] = record
        return record

    async def claim_outbox_batch(self, *, limit: int = 20) -> list[OutboxRecord]:
        now = datetime.now(tz=timezone.utc)
        async with self._lock:
            claimed: list[OutboxRecord] = []
            for record in sorted(self._outbox.values(), key=lambda item: item.id):
                if len(claimed) >= limit:
                    break
                if record.status != OUTBOX_PENDING:
                    continue
                if record.next_retry_at is not None and record.next_retry_at > now:
                    continue
                record.status = OUTBOX_PUBLISHING
                claimed.append(_copy_outbox(record))
            return claimed

    async def mark_outbox_published(self, outbox_id: int) -> None:
        async with self._lock:
            record = self._outbox.get(outbox_id)
            if record is None:
                return
            record.status = OUTBOX_PUBLISHED
            record.published_at = datetime.now(tz=timezone.utc)

    async def mark_outbox_failed(
        self, outbox_id: int, *, retry_count: int, next_retry_at: datetime
    ) -> None:
        async with self._lock:
            record = self._outbox.get(outbox_id)
            if record is None:
                return
            record.status = OUTBOX_PENDING
            record.retry_count = retry_count
            record.next_retry_at = next_retry_at

    async def reclaim_stale_outbox(self, *, stale_before: datetime) -> int:
        async with self._lock:
            reclaimed = 0
            for record in self._outbox.values():
                if record.status != OUTBOX_PUBLISHING:
                    continue
                if record.created_at is not None and record.created_at > stale_before:
                    continue
                record.status = OUTBOX_PENDING
                record.next_retry_at = None
                reclaimed += 1
            return reclaimed

    async def list_outbox(self, limit: int = 50) -> list[OutboxRecord]:
        async with self._lock:
            records = sorted(self._outbox.values(), key=lambda item: item.id, reverse=True)
            return [_copy_outbox(record) for record in records[:limit]]

    async def get_resume(
        self, invocation_id: int, resume_request_id: str
    ) -> ResumeRecord | None:
        async with self._lock:
            record = self._resumes.get((invocation_id, resume_request_id))
            if record is None:
                return None
            return ResumeRecord(
                invocation_id=record.invocation_id,
                resume_request_id=record.resume_request_id,
                thread_id=record.thread_id,
                status=record.status,
                payload=dict(record.payload),
                checkpoint_id=record.checkpoint_id,
                created_at=record.created_at,
            )

    async def create_resume(
        self,
        *,
        invocation_id: int,
        resume_request_id: str,
        thread_id: str,
        status: str,
        payload: dict[str, Any] | None = None,
        checkpoint_id: str | None = None,
    ) -> tuple[ResumeRecord, bool]:
        async with self._lock:
            key = (invocation_id, resume_request_id)
            existing = self._resumes.get(key)
            if existing is not None:
                return existing, False
            record = ResumeRecord(
                invocation_id=invocation_id,
                resume_request_id=resume_request_id,
                thread_id=thread_id,
                status=status,
                payload=payload or {},
                checkpoint_id=checkpoint_id,
                created_at=datetime.now(tz=timezone.utc),
            )
            self._resumes[key] = record
            return record, True

    async def update_resume(
        self,
        invocation_id: int,
        resume_request_id: str,
        *,
        status: str,
        payload: dict[str, Any] | None = None,
        checkpoint_id: str | None = None,
    ) -> ResumeRecord | None:
        async with self._lock:
            record = self._resumes.get((invocation_id, resume_request_id))
            if record is None:
                return None
            record.status = status
            if payload is not None:
                record.payload = payload
            if checkpoint_id is not None:
                record.checkpoint_id = checkpoint_id
            return ResumeRecord(
                invocation_id=record.invocation_id,
                resume_request_id=record.resume_request_id,
                thread_id=record.thread_id,
                status=record.status,
                payload=dict(record.payload),
                checkpoint_id=record.checkpoint_id,
                created_at=record.created_at,
            )

    async def list_records(self, limit: int = 50) -> list[ExecutionRecord]:
        async with self._lock:
            records = sorted(
                self._records.values(), key=lambda item: item.invocation_id, reverse=True
            )
            return [_copy_record(record) for record in records[:limit]]

    async def close(self) -> None:
        return None


def _copy_record(record: ExecutionRecord) -> ExecutionRecord:
    return ExecutionRecord(
        invocation_id=record.invocation_id,
        thread_id=record.thread_id,
        agent_type=record.agent_type,
        status=record.status,
        attempt_no=record.attempt_no,
        checkpoint_id=record.checkpoint_id,
        result=dict(record.result) if record.result else record.result,
        error=dict(record.error) if record.error else record.error,
        usage=dict(record.usage) if record.usage else record.usage,
        resume_request_id=record.resume_request_id,
        message_id=record.message_id,
        created_at=record.created_at,
        updated_at=record.updated_at,
    )


def _copy_outbox(record: OutboxRecord) -> OutboxRecord:
    return OutboxRecord(
        id=record.id,
        message_id=record.message_id,
        message_type=record.message_type,
        routing_key=record.routing_key,
        payload=dict(record.payload),
        status=record.status,
        retry_count=record.retry_count,
        next_retry_at=record.next_retry_at,
        created_at=record.created_at,
        published_at=record.published_at,
    )


# ---------------------------------------------------------------------------
# PostgreSQL implementation (production)
# ---------------------------------------------------------------------------


class PostgresExecutionRecordStore:
    """Durable store backed by PostgreSQL."""

    def __init__(self, dsn: str, *, min_size: int = 1, max_size: int = 5, timeout: int = 5) -> None:
        self._dsn = dsn
        self._min_size = min_size
        self._max_size = max_size
        self._timeout = timeout
        self._pool: Any = None

    async def connect(self) -> None:
        from psycopg_pool import AsyncConnectionPool

        self._pool = AsyncConnectionPool(
            self._dsn,
            min_size=self._min_size,
            max_size=self._max_size,
            timeout=self._timeout,
            open=False,
        )
        await self._pool.open(wait=True)

    async def close(self) -> None:
        if self._pool is not None:
            await self._pool.close()
            self._pool = None

    def _require_pool(self) -> Any:
        if self._pool is None:
            raise RuntimeError("PostgresExecutionRecordStore is not connected")
        return self._pool

    async def get(self, invocation_id: int) -> ExecutionRecord | None:
        pool = self._require_pool()
        async with pool.connection() as conn:
            async with conn.cursor() as cur:
                await cur.execute(
                    """
                    SELECT invocation_id, thread_id, agent_type, status, attempt_no,
                           checkpoint_id, result, error, usage, resume_request_id,
                           message_id, created_at, updated_at
                    FROM agent_execution_record
                    WHERE invocation_id = %s
                    """,
                    (invocation_id,),
                )
                row = await cur.fetchone()
        return _row_to_record(row) if row else None

    async def create(
        self,
        *,
        invocation_id: int,
        thread_id: str,
        agent_type: str,
        status: str = STATUS_RUNNING,
        attempt_no: int | None = None,
        message_id: str | None = None,
    ) -> tuple[ExecutionRecord, bool]:
        pool = self._require_pool()
        async with pool.connection() as conn:
            async with conn.cursor() as cur:
                await cur.execute(
                    """
                    INSERT INTO agent_execution_record
                        (invocation_id, thread_id, agent_type, status, attempt_no, message_id)
                    VALUES (%s, %s, %s, %s, %s, %s)
                    ON CONFLICT (invocation_id) DO NOTHING
                    RETURNING invocation_id, thread_id, agent_type, status, attempt_no,
                              checkpoint_id, result, error, usage, resume_request_id,
                              message_id, created_at, updated_at
                    """,
                    (invocation_id, thread_id, agent_type, status, attempt_no, message_id),
                )
                row = await cur.fetchone()
                if row is None:
                    await conn.rollback()
                    async with conn.cursor() as cur2:
                        await cur2.execute(
                            """
                            SELECT invocation_id, thread_id, agent_type, status, attempt_no,
                                   checkpoint_id, result, error, usage, resume_request_id,
                                   message_id, created_at, updated_at
                            FROM agent_execution_record
                            WHERE invocation_id = %s
                            """,
                            (invocation_id,),
                        )
                        row = await cur2.fetchone()
                    await conn.commit()
                    return (_row_to_record(row) if row else None, False)  # type: ignore[return-value]
                await conn.commit()
        return _row_to_record(row), True

    async def update(
        self,
        invocation_id: int,
        *,
        status: str | None = None,
        attempt_no: int | None = None,
        checkpoint_id: str | None = None,
        result: dict[str, Any] | None = None,
        error: dict[str, Any] | None = None,
        usage: dict[str, Any] | None = None,
        resume_request_id: str | None = None,
    ) -> ExecutionRecord | None:
        pool = self._require_pool()
        async with pool.connection() as conn:
            async with conn.cursor() as cur:
                await cur.execute(
                    """
                    UPDATE agent_execution_record
                    SET status = COALESCE(%s, status),
                        attempt_no = COALESCE(%s, attempt_no),
                        checkpoint_id = COALESCE(%s, checkpoint_id),
                        result = COALESCE(%s::jsonb, result),
                        error = COALESCE(%s::jsonb, error),
                        usage = COALESCE(%s::jsonb, usage),
                        resume_request_id = COALESCE(%s, resume_request_id),
                        updated_at = now()
                    WHERE invocation_id = %s
                    RETURNING invocation_id, thread_id, agent_type, status, attempt_no,
                              checkpoint_id, result, error, usage, resume_request_id,
                              message_id, created_at, updated_at
                    """,
                    (
                        status,
                        attempt_no,
                        checkpoint_id,
                        _json(result),
                        _json(error),
                        _json(usage),
                        resume_request_id,
                        invocation_id,
                    ),
                )
                row = await cur.fetchone()
                await conn.commit()
        return _row_to_record(row) if row else None

    async def update_with_outcome(
        self,
        invocation_id: int,
        *,
        status: str,
        checkpoint_id: str | None = None,
        result: dict[str, Any] | None = None,
        error: dict[str, Any] | None = None,
        usage: dict[str, Any] | None = None,
        outbox: OutboxMessage | None = None,
    ) -> ExecutionRecord | None:
        """Update the record and enqueue the result event in one transaction.

        The outbox row and the execution record are committed together: the
        result event can therefore never be lost, and the record can never
        claim a result that was not enqueued.
        """
        pool = self._require_pool()
        async with pool.connection() as conn:
            try:
                async with conn.cursor() as cur:
                    await cur.execute(
                        """
                        UPDATE agent_execution_record
                        SET status = %s,
                            checkpoint_id = COALESCE(%s, checkpoint_id),
                            result = COALESCE(%s::jsonb, result),
                            error = COALESCE(%s::jsonb, error),
                            usage = COALESCE(%s::jsonb, usage),
                            updated_at = now()
                        WHERE invocation_id = %s
                        RETURNING invocation_id, thread_id, agent_type, status, attempt_no,
                                  checkpoint_id, result, error, usage, resume_request_id,
                                  message_id, created_at, updated_at
                        """,
                        (
                            status,
                            checkpoint_id,
                            _json(result),
                            _json(error),
                            _json(usage),
                            invocation_id,
                        ),
                    )
                    row = await cur.fetchone()
                    if outbox is not None:
                        await cur.execute(
                            """
                            INSERT INTO agent_outbox
                                (message_id, message_type, routing_key, payload)
                            VALUES (%s, %s, %s, %s::jsonb)
                            """,
                            (
                                outbox.message_id,
                                outbox.message_type,
                                outbox.routing_key,
                                _json(outbox.payload),
                            ),
                        )
                await conn.commit()
            except Exception:
                await conn.rollback()
                raise
        return _row_to_record(row) if row else None

    async def claim_outbox_batch(self, *, limit: int = 20) -> list[OutboxRecord]:
        """Claim pending rows for delivery (SKIP LOCKED keeps replicas apart)."""
        pool = self._require_pool()
        async with pool.connection() as conn:
            async with conn.cursor() as cur:
                await cur.execute(
                    """
                    SELECT id, message_id, message_type, routing_key, payload, status,
                           retry_count, next_retry_at, created_at, published_at
                    FROM agent_outbox
                    WHERE status = %s
                      AND (next_retry_at IS NULL OR next_retry_at <= now())
                    ORDER BY created_at
                    LIMIT %s
                    FOR UPDATE SKIP LOCKED
                    """,
                    (OUTBOX_PENDING, limit),
                )
                rows = await cur.fetchall()
                if rows:
                    await cur.execute(
                        "UPDATE agent_outbox SET status = %s WHERE id = ANY(%s)",
                        (OUTBOX_PUBLISHING, [row[0] for row in rows]),
                    )
                await conn.commit()
        return [_row_to_outbox(row) for row in rows]

    async def mark_outbox_published(self, outbox_id: int) -> None:
        pool = self._require_pool()
        async with pool.connection() as conn:
            async with conn.cursor() as cur:
                await cur.execute(
                    """
                    UPDATE agent_outbox
                    SET status = %s, published_at = now()
                    WHERE id = %s
                    """,
                    (OUTBOX_PUBLISHED, outbox_id),
                )
                await conn.commit()

    async def mark_outbox_failed(
        self, outbox_id: int, *, retry_count: int, next_retry_at: datetime
    ) -> None:
        pool = self._require_pool()
        async with pool.connection() as conn:
            async with conn.cursor() as cur:
                await cur.execute(
                    """
                    UPDATE agent_outbox
                    SET status = %s, retry_count = %s, next_retry_at = %s
                    WHERE id = %s
                    """,
                    (OUTBOX_PENDING, retry_count, next_retry_at, outbox_id),
                )
                await conn.commit()

    async def reclaim_stale_outbox(self, *, stale_before: datetime) -> int:
        pool = self._require_pool()
        async with pool.connection() as conn:
            async with conn.cursor() as cur:
                await cur.execute(
                    """
                    UPDATE agent_outbox
                    SET status = %s, next_retry_at = NULL
                    WHERE status = %s AND created_at <= %s
                    """,
                    (OUTBOX_PENDING, OUTBOX_PUBLISHING, stale_before),
                )
                reclaimed = cur.rowcount
                await conn.commit()
        return int(reclaimed or 0)

    async def list_outbox(self, limit: int = 50) -> list[OutboxRecord]:
        pool = self._require_pool()
        async with pool.connection() as conn:
            async with conn.cursor() as cur:
                await cur.execute(
                    """
                    SELECT id, message_id, message_type, routing_key, payload, status,
                           retry_count, next_retry_at, created_at, published_at
                    FROM agent_outbox
                    ORDER BY id DESC
                    LIMIT %s
                    """,
                    (limit,),
                )
                rows = await cur.fetchall()
        return [_row_to_outbox(row) for row in rows]

    async def get_resume(
        self, invocation_id: int, resume_request_id: str
    ) -> ResumeRecord | None:
        pool = self._require_pool()
        async with pool.connection() as conn:
            async with conn.cursor() as cur:
                await cur.execute(
                    """
                    SELECT invocation_id, resume_request_id, thread_id, status, payload,
                           checkpoint_id, created_at
                    FROM agent_resume_record
                    WHERE invocation_id = %s AND resume_request_id = %s
                    """,
                    (invocation_id, resume_request_id),
                )
                row = await cur.fetchone()
        return _row_to_resume(row) if row else None
    async def create_resume(
        self,
        *,
        invocation_id: int,
        resume_request_id: str,
        thread_id: str,
        status: str,
        payload: dict[str, Any] | None = None,
        checkpoint_id: str | None = None,
    ) -> tuple[ResumeRecord, bool]:
        pool = self._require_pool()
        async with pool.connection() as conn:
            async with conn.cursor() as cur:
                await cur.execute(
                    """
                    INSERT INTO agent_resume_record
                        (invocation_id, resume_request_id, thread_id, status, payload, checkpoint_id)
                    VALUES (%s, %s, %s, %s, %s::jsonb, %s)
                    ON CONFLICT (invocation_id, resume_request_id) DO NOTHING
                    RETURNING invocation_id, resume_request_id, thread_id, status, payload,
                              checkpoint_id, created_at
                    """,
                    (
                        invocation_id,
                        resume_request_id,
                        thread_id,
                        status,
                        _json(payload or {}),
                        checkpoint_id,
                    ),
                )
                row = await cur.fetchone()
                if row is None:
                    await conn.rollback()
                    async with conn.cursor() as cur2:
                        await cur2.execute(
                            """
                            SELECT invocation_id, resume_request_id, thread_id, status, payload,
                                   checkpoint_id, created_at
                            FROM agent_resume_record
                            WHERE invocation_id = %s AND resume_request_id = %s
                            """,
                            (invocation_id, resume_request_id),
                        )
                        row = await cur2.fetchone()
                    await conn.commit()
                    return (_row_to_resume(row) if row else None, False)  # type: ignore[return-value]
                await conn.commit()
        return _row_to_resume(row), True

    async def update_resume(
        self,
        invocation_id: int,
        resume_request_id: str,
        *,
        status: str,
        payload: dict[str, Any] | None = None,
        checkpoint_id: str | None = None,
    ) -> ResumeRecord | None:
        pool = self._require_pool()
        async with pool.connection() as conn:
            async with conn.cursor() as cur:
                await cur.execute(
                    """
                    UPDATE agent_resume_record
                    SET status = %s,
                        payload = COALESCE(%s::jsonb, payload),
                        checkpoint_id = COALESCE(%s, checkpoint_id)
                    WHERE invocation_id = %s AND resume_request_id = %s
                    RETURNING invocation_id, resume_request_id, thread_id, status, payload,
                              checkpoint_id, created_at
                    """,
                    (status, _json(payload), checkpoint_id, invocation_id, resume_request_id),
                )
                row = await cur.fetchone()
                await conn.commit()
        return _row_to_resume(row) if row else None

    async def list_records(self, limit: int = 50) -> list[ExecutionRecord]:
        pool = self._require_pool()
        async with pool.connection() as conn:
            async with conn.cursor() as cur:
                await cur.execute(
                    """
                    SELECT invocation_id, thread_id, agent_type, status, attempt_no,
                           checkpoint_id, result, error, usage, resume_request_id,
                           message_id, created_at, updated_at
                    FROM agent_execution_record
                    ORDER BY invocation_id DESC
                    LIMIT %s
                    """,
                    (limit,),
                )
                rows = await cur.fetchall()
        return [_row_to_record(row) for row in rows]


def _json(value: Any) -> str | None:
    if value is None:
        return None
    return json.dumps(value, ensure_ascii=False, default=str)


def _row_to_record(row: Any) -> ExecutionRecord:
    return ExecutionRecord(
        invocation_id=int(row[0]),
        thread_id=row[1],
        agent_type=row[2],
        status=row[3],
        attempt_no=row[4],
        checkpoint_id=row[5],
        result=row[6],
        error=row[7],
        usage=row[8],
        resume_request_id=row[9],
        message_id=row[10],
        created_at=row[11],
        updated_at=row[12],
    )


def _row_to_resume(row: Any) -> ResumeRecord:
    return ResumeRecord(
        invocation_id=int(row[0]),
        resume_request_id=row[1],
        thread_id=row[2],
        status=row[3],
        payload=row[4] or {},
        checkpoint_id=row[5],
        created_at=row[6],
    )


def _row_to_outbox(row: Any) -> OutboxRecord:
    return OutboxRecord(
        id=int(row[0]),
        message_id=row[1],
        message_type=row[2],
        routing_key=row[3],
        payload=row[4] or {},
        status=row[5],
        retry_count=int(row[6] or 0),
        next_retry_at=row[7],
        created_at=row[8],
        published_at=row[9],
    )


SCHEMA_PATH = Path(__file__).resolve().parent / "schema.sql"


async def apply_schema(dsn: str, *, timeout: int = 10) -> None:
    """Create the agent tables if they do not exist yet."""
    import psycopg

    ddl = SCHEMA_PATH.read_text(encoding="utf-8")
    async with await psycopg.AsyncConnection.connect(dsn, connect_timeout=timeout) as conn:
        await conn.execute(ddl)
        await conn.commit()
