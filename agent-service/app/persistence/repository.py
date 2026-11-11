"""Durable execution records — the backbone of the idempotency guarantee.

Two stores implement the same protocol:

* :class:`PostgresExecutionRecordStore` — production, backed by the
  ``agent_execution_record`` / ``agent_resume_record`` tables
  (see ``schema.sql``).
* :class:`InMemoryExecutionRecordStore` — used by the tests and by the local
  demo when PostgreSQL is not available.

The unique constraints of the tables implement the protocol idempotency rules:

* ``AgentExecute``  -> ``invocation_id``
* ``AgentResume``   -> ``invocation_id`` + ``resume_request_id``

A redelivered message therefore never starts a second agent run: the store
reports that the record already exists and the executor returns the recorded
terminal result.
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


SCHEMA_PATH = Path(__file__).resolve().parent / "schema.sql"


async def apply_schema(dsn: str, *, timeout: int = 10) -> None:
    """Create the agent tables if they do not exist yet."""
    import psycopg

    ddl = SCHEMA_PATH.read_text(encoding="utf-8")
    async with await psycopg.AsyncConnection.connect(dsn, connect_timeout=timeout) as conn:
        await conn.execute(ddl)
        await conn.commit()
