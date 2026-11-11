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
from app.messaging.producer import EventPublisher
from app.persistence.checkpoint import memory_checkpointer, postgres_checkpointer
from app.persistence.repository import (
    InMemoryExecutionRecordStore,
    PostgresExecutionRecordStore,
    apply_schema,
)
from app.runtime.executor import AgentExecutor
from app.runtime.registry import registry as default_registry

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
            store = await _create_store(settings, persistence_enabled)
            application.state.store = store

            checkpointer = await _create_checkpointer(stack, settings, persistence_enabled)
            application.state.checkpointer = checkpointer

            registry = default_registry
            application.state.registry = registry

            executor = AgentExecutor(
                store=store,
                checkpointer=checkpointer,
                registry=registry,
                settings=settings,
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

            application.state.stopping = False
            task: asyncio.Task | None = None
            if messaging_enabled:
                task = asyncio.create_task(_messaging_supervisor(application, consumer))
                application.state.messaging_task = task

            try:
                yield
            finally:
                application.state.stopping = True
                if task is not None:
                    task.cancel()
                    try:
                        await task
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
    return application


async def _create_store(settings: Settings, persistence_enabled: bool) -> Any:
    if not persistence_enabled:
        logger.warning("persistence is disabled, using the in-memory execution store")
        return InMemoryExecutionRecordStore()
    store = PostgresExecutionRecordStore(
        settings.postgres_dsn,
        min_size=settings.postgres_pool_min_size,
        max_size=settings.postgres_pool_max_size,
        timeout=settings.postgres_connect_timeout,
    )
    try:
        await store.connect()
        await apply_schema(settings.postgres_dsn, timeout=settings.postgres_connect_timeout)
        return store
    except Exception:
        logger.exception(
            "postgres is not reachable, falling back to the in-memory execution store"
        )
        return InMemoryExecutionRecordStore()


async def _create_checkpointer(
    stack: AsyncExitStack, settings: Settings, persistence_enabled: bool
) -> Any:
    if not persistence_enabled:
        logger.warning("persistence is disabled, using the in-memory checkpointer")
        return memory_checkpointer()
    try:
        return await stack.enter_async_context(postgres_checkpointer(settings.postgres_dsn))
    except Exception:
        logger.exception(
            "the postgres checkpointer is not available, falling back to the in-memory saver"
        )
        return memory_checkpointer()


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
