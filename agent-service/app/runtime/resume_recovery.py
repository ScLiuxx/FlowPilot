"""Recovery of the resume requests whose worker did not come back.

A resume that is continued by a worker that dies is stuck: the ledger of
``agent_resume_record`` holds the claim, and the broker redelivers the resume
command — but only if the message was not acknowledged yet.

That is not guaranteed. A worker that loses its connection to the broker but
keeps running holds the lease; a duplicate delivery finds the live lease, waits
for it and gives up. The consumer acknowledges that message, so nothing is left
in the broker. When the worker finally dies, no message triggers the takeover
anymore and the resume is stuck forever.

This scanner closes that gap. It walks the ledger on its own, finds the resume
requests whose lease expired and re-publishes the resume command of each of
them. The delivery does not depend on the broker redelivering anything.

The lease is *not* claimed here. It is acquired by the consumer that continues
the thread, because that is the only place where the acquisition can be
correct: the claim fences the workers against each other, so it has to happen
where the thread is actually continued. Two scanners of two replicas may
therefore publish the same command — the lease decides which one continues the
thread, and the other one gives up.
"""

from __future__ import annotations

import asyncio
import time
import uuid
from datetime import datetime, timedelta, timezone
from typing import Any

from app.config.logging import get_logger
from app.config.settings import Settings, get_settings
from app.messaging.events import (
    ROUTING_AGENT_RESUME,
    AgentResumePayload,
    MessageType,
    build_envelope,
)
from app.messaging.producer import EventPublisher
from app.persistence.checkpoint import latest_checkpoint_id
from app.persistence.repository import (
    STATUS_INTERRUPTED,
    STATUS_RUNNING,
    STATUS_SUCCEEDED,
    ResumeRecord,
)

logger = get_logger(__name__)


class ResumeRecoveryScanner:
    """Re-publishes the resume commands whose worker did not come back."""

    def __init__(
        self,
        *,
        store: Any,
        checkpointer: Any,
        publisher: EventPublisher,
        settings: Settings | None = None,
    ) -> None:
        self._store = store
        self._checkpointer = checkpointer
        self._publisher = publisher
        self._settings = settings or get_settings()
        self._stopping = False
        # The requests that were published recently, so that a command that is
        # still waiting in the queue is not published over and over again.
        self._published: dict[tuple[int, str], float] = {}

    def stop(self) -> None:
        self._stopping = True

    async def run(self) -> None:
        """Scan until the task is cancelled."""
        interval = self._settings.resume_recovery_scan_seconds
        while not self._stopping:
            try:
                await self.scan_once()
            except asyncio.CancelledError:  # pragma: no cover - shutdown
                raise
            except Exception:  # pragma: no cover - defensive
                logger.exception("the resume recovery scanner failed")
            await asyncio.sleep(interval)

    async def scan_once(self) -> int:
        """Recover the stale resume requests. Returns how many were recovered."""
        if not self._publisher.connected:
            # Without a broker nothing can be published, so there is nothing to
            # do: the rows stay stale and the next scan tries again.
            return 0

        stale_before = datetime.now(tz=timezone.utc) - timedelta(
            seconds=self._settings.resume_recovery_lease_grace_seconds
        )
        stale = await self._store.list_stale_resumes(
            stale_before=stale_before,
            limit=self._settings.resume_recovery_batch_size,
        )
        recovered = 0
        for record in stale:
            try:
                if await self._recover(record):
                    recovered += 1
            except asyncio.CancelledError:  # pragma: no cover - shutdown
                raise
            except Exception:  # pragma: no cover - defensive
                logger.exception(
                    "the resume could not be recovered",
                    extra={"resumeRequestId": record.resume_request_id},
                )
        if recovered:
            logger.info(
                "stale resume requests recovered",
                extra={"count": recovered},
            )
        return recovered

    async def _recover(self, record: ResumeRecord) -> bool:
        """Re-publish the command of one stale resume request."""
        key = (record.invocation_id, record.resume_request_id)
        last_published = self._published.get(key)
        now = time.monotonic()
        if last_published is not None and now - last_published < self._cooldown():
            # The command was published recently and is probably still on its
            # way to a consumer.
            return False

        invocation = await self._store.get(record.invocation_id)
        if invocation is None:
            logger.warning(
                "the invocation of a stale resume does not exist",
                extra={
                    "invocationId": record.invocation_id,
                    "resumeRequestId": record.resume_request_id,
                },
            )
            return False
        if invocation.status == STATUS_SUCCEEDED:
            # The invocation already finished: the ledger was closed by a
            # result that arrived before the worker died.
            await self._store.update_resume(
                record.invocation_id,
                record.resume_request_id,
                status=invocation.status,
            )
            return False
        if invocation.status not in (STATUS_INTERRUPTED, STATUS_RUNNING):
            # The invocation is in a state that cannot be continued any more.
            logger.info(
                "the invocation of a stale resume cannot be continued",
                extra={
                    "invocationId": record.invocation_id,
                    "resumeRequestId": record.resume_request_id,
                    "status": invocation.status,
                },
            )
            return False

        # Without a checkpoint there is nothing to continue: the graph never
        # got far enough to be interrupted.
        checkpoint_id = await latest_checkpoint_id(self._checkpointer, record.thread_id)
        if checkpoint_id is None:
            logger.info(
                "the thread of a stale resume has no checkpoint",
                extra={
                    "invocationId": record.invocation_id,
                    "resumeRequestId": record.resume_request_id,
                },
            )
            return False

        await self._publish(record)
        self._published[key] = now
        logger.warning(
            "a stale resume request was recovered",
            extra={
                "invocationId": record.invocation_id,
                "resumeRequestId": record.resume_request_id,
                "threadId": record.thread_id,
                "checkpointId": checkpoint_id,
                "takeoverCount": record.takeover_count,
            },
        )
        return True

    def _cooldown(self) -> float:
        """How long a published command is not published again."""
        return float(self._settings.resume_lease_seconds)

    async def _publish(self, record: ResumeRecord) -> None:
        """Publish the resume command of a recovered request.

        The command carries the same idempotency key, so the consumer that
        picks it up finds the ledger of the request, takes the lease over and
        continues the thread instead of starting a second run.
        """
        routing = record.payload or {}
        payload = AgentResumePayload(
            invocationId=record.invocation_id,
            threadId=record.thread_id,
            resumeRequestId=record.resume_request_id,
            approvalId=routing.get("approvalId"),
            resumeValue=routing.get("resumeValue") or {},
        )
        envelope = build_envelope(
            MessageType.AGENT_RESUME,
            workflow_execution_id=int(
                routing.get("workflowExecutionId") or record.invocation_id
            ),
            trace_id=str(routing.get("traceId") or uuid.uuid4().hex),
            payload=payload,
            node_execution_id=routing.get("nodeExecutionId"),
            attempt_no=routing.get("attemptNo"),
            invocation_id=record.invocation_id,
        )
        await self._publisher.publish(envelope, ROUTING_AGENT_RESUME)
