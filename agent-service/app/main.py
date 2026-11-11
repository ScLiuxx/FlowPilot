"""FastAPI application of the FlowPilot agent service.

The service is an *internal* service: the HTTP surface is limited to health
probes and to the internal admin/debug endpoints. The business work happens
asynchronously, driven by the RabbitMQ command queues.
"""

from __future__ import annotations

import asyncio
from contextlib import AsyncExitStack, asynccontextmanager
from typing import Any, AsyncIterator

from fastapi import FastAPI

from app.api import health, internal
from app.config.logging import configure_logging, get_logger
from app.config.settings import Settings, get_settings
from app.messaging.consumer import AgentMessageConsumer
from app.messaging.outbox_publisher import OutboxPublisher
from app.messaging.producer import EventPublisher
from app.persistence.checkpoint import memory_checkpointer, postgres_checkpointer
from app.persistence.repository import (
    InMemoryExecutionRecordStore,
    PostgresExecutionRecordStore,
    apply_schema,
)
from app.runtime.context import (
    IdempotencyJournal,
    InMemoryIdempotencyJournal,
    RedisIdempotencyJournal,
)
from app.runtime.executor import AgentExecutor
from app.runtime.registry import registry as default_registry
from app.telemetry import configure_tracing, flush_tracing

logger = get_logger(__name__)

DESCRIPTION = (
    "FlowPilot Agent Runtime — executes LangGraph agents for the FlowPilot "
    "workflow engine and reports the result back to the Java backend."
)


def create_app(
    *,
    settings: Settings | None = None,
    start_messaging: bool | None = None,
    start_persistence: bool | None = None,
) -> FastAPI:
    """Build the FastAPI application.

    ``start_messaging`` / ``start_persistence`` allow the tests to run the
    application without any infrastructure.
    """
    settings = settings or get_settings()
    messaging_enabled = settings.messaging_enabled if start_messaging is None else start_messaging
    persistence_enabled = (
        settings.persistence_enabled if start_persistence is None else start_persistence
    )
    if settings.otel_enabled:
        configure_tracing(settings)

    @asynccontextmanager
    async def lifespan(application: FastAPI) -> AsyncIterator[None]:
        configure_logging(settings.log_level, settings.log_json)
        logger.info(
            "agent service starting",
            extra={
                "messaging": messaging_enabled,
                "persistence": persistence_enabled,
                "environment": settings.environment,
            },
        )
        async with AsyncExitStack() as stack:
            store, store_degraded = await _create_store(settings, persistence_enabled)
            application.state.store = store

            checkpointer, checkpointer_degraded = await _create_checkpointer(
                stack, settings, persistence_enabled
            )
            application.state.checkpointer = checkpointer
            application.state.persistence_degraded = store_degraded or checkpointer_degraded

            registry = default_registry
            application.state.registry = registry

            journal = _create_journal(settings)
            application.state.journal = journal

            executor = AgentExecutor(
                store=store,
                checkpointer=checkpointer,
                registry=registry,
                settings=settings,
                journal=journal,
            )
            application.state.executor = executor

            publisher = EventPublisher(settings=settings)
            application.state.publisher = publisher

            consumer = AgentMessageConsumer(
                executor=executor,
                publisher=publisher,
                settings=settings,
            )
            application.state.consumer = consumer

            outbox = OutboxPublisher(store=store, publisher=publisher, settings=settings)
            application.state.outbox_publisher = outbox

            application.state.stopping = False
            task: asyncio.Task | None = None
            outbox_task: asyncio.Task | None = None
            if messaging_enabled:
                task = asyncio.create_task(_messaging_supervisor(application, consumer))
                application.state.messaging_task = task
                outbox_task = asyncio.create_task(outbox.run())
                application.state.outbox_task = outbox_task

            try:
                yield
            finally:
                application.state.stopping = True
                outbox.stop()
                for background in (outbox_task, task):
                    if background is None:
                        continue
                    background.cancel()
                    try:
                        await background
                    except (asyncio.CancelledError, Exception):  # pragma: no cover
                        pass
                try:
                    await consumer.stop()
                except Exception:  # pragma: no cover - best effort shutdown
                    logger.exception("failed to stop the consumer")
                try:
                    await publisher.close()
                except Exception:  # pragma: no cover - best effort shutdown
                    logger.exception("failed to close the publisher")
                try:
                    await store.close()
                except Exception:  # pragma: no cover - best effort shutdown
                    logger.exception("failed to close the execution store")
                try:
                    await journal.close()
                except Exception:  # pragma: no cover - best effort shutdown
                    logger.exception("failed to close the idempotency journal")
                flush_tracing()
                logger.info("agent service stopped")

    application = FastAPI(
        title="FlowPilot Agent Service",
        description=DESCRIPTION,
        version=settings.version,
        lifespan=lifespan,
    )
    application.state.settings = settings
    application.include_router(health.router)
    application.include_router(internal.router)
    if settings.otel_enabled:
        from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor

        FastAPIInstrumentor.instrument_app(application)
    return application


def _create_journal(settings: Settings) -> IdempotencyJournal:
    """Build the idempotency journal shared by the replicas of the service."""
    url = (settings.redis_url or "").strip()
    if not url:
        logger.warning(
            "no redis url configured, using the in-memory idempotency journal: "
            "the side effects in front of an interrupt are not shared between "
            "the replicas of the service"
        )
        return InMemoryIdempotencyJournal()
    return RedisIdempotencyJournal(
        url=url, ttl_seconds=settings.idempotency_journal_ttl_seconds
    )


async def _create_store(settings: Settings, persistence_enabled: bool) -> tuple[Any, bool]:
    """Build the execution record store.

    Returns the store and whether the service runs in degraded mode. A
    PostgreSQL that cannot be reached is a hard error unless the operator
    explicitly allowed the degradation.
    """
    if not persistence_enabled:
        logger.warning("persistence is disabled, using the in-memory execution store")
        return InMemoryExecutionRecordStore(), False
    store = PostgresExecutionRecordStore(
        settings.postgres_dsn,
        min_size=settings.postgres_pool_min_size,
        max_size=settings.postgres_pool_max_size,
        timeout=settings.postgres_connect_timeout,
    )
    try:
        await store.connect()
        await apply_schema(settings.postgres_dsn, timeout=settings.postgres_connect_timeout)
    except Exception:
        await store.close()
        if settings.persistence_required:
            logger.error(
                "postgres is not reachable, the agent service refuses to start: "
                "the checkpoints and the idempotency ledger would be lost"
            )
            raise
        logger.warning(
            "DEGRADED MODE: postgres is not reachable, the in-memory execution "
            "store is used. The agent loses its checkpoints and its idempotency "
            "guarantee — do not run the service this way in production."
        )
        return InMemoryExecutionRecordStore(), True
    return store, False


async def _create_checkpointer(
    stack: AsyncExitStack, settings: Settings, persistence_enabled: bool
) -> tuple[Any, bool]:
    """Build the LangGraph checkpointer (see :func:`_create_store`)."""
    if not persistence_enabled:
        logger.warning("persistence is disabled, using the in-memory checkpointer")
        return memory_checkpointer(), False
    try:
        return await stack.enter_async_context(postgres_checkpointer(settings.postgres_dsn)), False
    except Exception:
        if settings.persistence_required:
            logger.error(
                "the postgres checkpointer is not available, the agent service "
                "refuses to start: the threads could not be resumed"
            )
            raise
        logger.warning(
            "DEGRADED MODE: the postgres checkpointer is not available, the "
            "in-memory saver is used. The agent threads cannot be resumed."
        )
        return memory_checkpointer(), True


async def _messaging_supervisor(application: FastAPI, consumer: AgentMessageConsumer) -> None:
    """Start the consumer and keep retrying until the broker is reachable."""
    delay = application.state.settings.rabbitmq_reconnect_delay_seconds
    while not application.state.stopping:
        try:
            await consumer.start()
            break
        except asyncio.CancelledError:  # pragma: no cover
            raise
        except Exception as exc:
            logger.warning(
                "rabbitmq is not reachable, retrying",
                extra={"error": str(exc), "errorType": type(exc).__name__},
            )
            await asyncio.sleep(delay)
    while not application.state.stopping:
        await asyncio.sleep(1)


app = create_app()
