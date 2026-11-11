"""The idempotency journal of the side effects in front of an interrupt.

LangGraph re-executes the node that raised the interrupt when the thread is
resumed. The journal guarantees that the side effect of that node happens
exactly once — also when several replicas of the service resume the same
thread, therefore the Redis implementation has to be atomic.
"""

from __future__ import annotations

import asyncio
import os

import pytest

from app.runtime.context import (
    JOURNAL_KEY_PREFIX,
    InMemoryIdempotencyJournal,
    RedisIdempotencyJournal,
)

REDIS_URL = os.environ.get("REDIS_URL", "redis://localhost:6379/0")


def _redis_available() -> bool:
    try:
        import redis

        client = redis.Redis.from_url(REDIS_URL, socket_connect_timeout=1)
        client.ping()
        client.close()
        return True
    except Exception:
        return False


redis_available = _redis_available()


async def test_the_in_memory_journal_registers_a_key_only_once():
    journal = InMemoryIdempotencyJournal()

    assert await journal.record_once("approval-request:1", {"reviewId": "1"}) is True
    assert await journal.record_once("approval-request:1", {"reviewId": "1"}) is False
    assert await journal.contains("approval-request:1") is True
    assert await journal.contains("approval-request:2") is False


async def test_the_concurrent_registrations_of_the_in_memory_journal_are_serialised():
    journal = InMemoryIdempotencyJournal()

    results = await asyncio.gather(
        *(journal.record_once("approval-request:1") for _ in range(20))
    )

    assert results.count(True) == 1


@pytest.mark.skipif(not redis_available, reason="Redis is not reachable")
async def test_the_redis_journal_registers_a_key_only_once():
    journal = RedisIdempotencyJournal(url=REDIS_URL, ttl_seconds=60)
    key = f"test-{os.getpid()}"
    try:
        assert await journal.record_once(key, {"reviewId": "1"}) is True
        assert await journal.record_once(key, {"reviewId": "1"}) is False
        assert await journal.contains(key) is True
        assert await journal.contains(f"{key}-other") is False
    finally:
        await journal.close()
        import redis.asyncio as redis_asyncio

        client = redis_asyncio.from_url(REDIS_URL, decode_responses=True)
        await client.delete(f"{JOURNAL_KEY_PREFIX}{key}")
        await client.aclose()


@pytest.mark.skipif(not redis_available, reason="Redis is not reachable")
async def test_the_concurrent_registrations_of_the_redis_journal_are_atomic():
    journal = RedisIdempotencyJournal(url=REDIS_URL, ttl_seconds=60)
    key = f"test-concurrent-{os.getpid()}"
    try:
        results = await asyncio.gather(
            *(journal.record_once(key) for _ in range(20))
        )
        assert results.count(True) == 1
    finally:
        await journal.close()
        import redis.asyncio as redis_asyncio

        client = redis_asyncio.from_url(REDIS_URL, decode_responses=True)
        await client.delete(f"{JOURNAL_KEY_PREFIX}{key}")
        await client.aclose()
