"""Delivery of the transactional outbox to RabbitMQ.

The agent service writes its result events to the ``agent_outbox`` table in
the very same transaction as the execution record (architecture design,
chapter 8.4). This background task delivers the pending rows to the broker and
marks them as published.

The delivery is *at least once*: a crash between the publisher confirm and the
update of the row leads to a repeated event. The Java backend therefore has to
stay idempotent — which it is, the invocation id is the business key.
"""

from __future__ import annotations

import asyncio
from datetime import datetime, timedelta, timezone
from typing import Any

from app.config.logging import get_logger
from app.config.settings import Settings, get_settings
from app.messaging.events import MessageEnvelope
from app.messaging.producer import EventPublisher
from app.persistence.repository import OutboxRecord

logger = get_logger(__name__)


class OutboxPublisher:
    """Publishes the pending outbox rows to RabbitMQ."""

    def __init__(
        self,
        *,
        store: Any,
        publisher: EventPublisher,
        settings: Settings | None = None,
    ) -> None:
        self._store = store
        self._publisher = publisher
        self._settings = settings or get_settings()
        self._stopping = False

    def stop(self) -> None:
        self._stopping = True

    async def run(self) -> None:
        """Deliver the outbox until the task is cancelled."""
        interval = self._settings.outbox_poll_interval_seconds
        while not self._stopping:
            try:
                await self.publish_pending()
            except asyncio.CancelledError:  # pragma: no cover - shutdown
                raise
            except Exception:  # pragma: no cover - defensive
                logger.exception("the outbox publisher failed")
            await asyncio.sleep(interval)

    async def publish_pending(self) -> int:
        """Deliver one batch of pending rows. Returns the number of rows."""
        if not self._publisher.connected:
            # The broker is not reachable: the rows stay pending and are
            # delivered as soon as the connection is back.
            return 0
        await self._reclaim_stale()
        batch = await self._store.claim_outbox_batch(
            limit=self._settings.outbox_batch_size
        )
        for record in batch:
            await self._deliver(record)
        return len(batch)

    async def _deliver(self, record: OutboxRecord) -> None:
        try:
            envelope = MessageEnvelope.model_validate(record.payload)
            await self._publisher.publish(envelope, record.routing_key)
        except Exception as exc:
            await self._reschedule(record, exc)
            return
        await self._store.mark_outbox_published(record.id)

    async def _reschedule(self, record: OutboxRecord, exc: Exception) -> None:
        """Return a failed row to PENDING and delay the next attempt."""
        retry_count = record.retry_count + 1
        delay = min(
            float(self._settings.outbox_max_retry_seconds),
            float(2 ** min(retry_count, 6)),
        )
        next_retry_at = datetime.now(tz=timezone.utc) + timedelta(seconds=delay)
        await self._store.mark_outbox_failed(
            record.id, retry_count=retry_count, next_retry_at=next_retry_at
        )
        logger.warning(
            "outbox delivery failed, the event is retried",
            extra={
                "outboxId": record.id,
                "messageType": record.message_type,
                "retryCount": retry_count,
                "retryInSeconds": delay,
                "error": str(exc),
                "errorType": type(exc).__name__,
            },
        )

    async def _reclaim_stale(self) -> None:
        """Recover the rows whose publisher died while they were claimed."""
        stale_before = datetime.now(tz=timezone.utc) - timedelta(
            seconds=self._settings.outbox_publishing_timeout_seconds
        )
        reclaimed = await self._store.reclaim_stale_outbox(stale_before=stale_before)
        if reclaimed:
            logger.warning(
                "outbox rows reclaimed after a publishing timeout",
                extra={"count": reclaimed},
            )
