"""Execution context handed to every graph node.

The context is injected into the LangGraph nodes through the runtime context
(``Runtime.context``). It carries the immutable invocation metadata, the
configured budgets and the collaborators (LLM, GitHub tool) a node may use.

It is deliberately *not* part of the checkpointed graph state: only the plain
counters in :class:`app.runtime.state.AgentState` are persisted, so that a
thread can be resumed by a different process.
"""

from __future__ import annotations

import asyncio
import inspect
import json
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Any, Awaitable, Callable

from app.config.logging import get_logger
from app.runtime.errors import BudgetExceededError

logger = get_logger(__name__)

#: Prefix of the Redis keys of the idempotency journal.
JOURNAL_KEY_PREFIX = "flowpilot:idempotency:"


@dataclass(frozen=True)
class ExecutionLimits:
    """Budgets taken from the ``AgentExecute`` payload config."""

    max_iterations: int = 12
    max_tool_calls: int = 20
    max_tokens: int = 12000
    timeout_seconds: int = 300

    @classmethod
    def from_config(cls, config: Any) -> "ExecutionLimits":
        return cls(
            max_iterations=int(getattr(config, "maxIterations", None) or 12),
            max_tool_calls=int(getattr(config, "maxToolCalls", None) or 20),
            max_tokens=int(getattr(config, "maxTokens", None) or 12000),
            timeout_seconds=int(getattr(config, "timeoutSeconds", None) or 300),
        )


@dataclass
class ExecutionCounters:
    """Statistics of the *current* run of one invocation.

    The counters live on the context and not in the checkpointed state: the
    state is written by every graph and would therefore be polluted by the
    statistics of a single run. The values are reported to the Java backend
    together with the result of the invocation.
    """

    llm_calls: int = 0
    tool_calls: int = 0
    tool_failures: int = 0

    def record_llm_call(self) -> None:
        self.llm_calls += 1

    def record_tool_call(self) -> None:
        self.tool_calls += 1

    def record_tool_failure(self) -> None:
        self.tool_failures += 1


class IdempotencyJournal(ABC):
    """Journal of already performed side effects.

    LangGraph re-executes the node that raised an ``interrupt()`` when the
    thread is resumed. Every side effect performed *before* the interrupt must
    therefore be idempotent. Instead of relying on the side effect itself being
    idempotent, the node registers the effect in this journal first: the
    journal is keyed, so the second execution of the node observes the key and
    skips the effect.

    The journal has to be shared by every replica of the service, therefore the
    implementation is asynchronous (Redis) and the registration is atomic.
    """

    @abstractmethod
    async def record_once(self, key: str, payload: dict[str, Any] | None = None) -> bool:
        """Register ``key``. Returns ``True`` only for the first registration."""

    @abstractmethod
    async def contains(self, key: str) -> bool:
        """Whether ``key`` was already registered."""

    async def close(self) -> None:
        """Release the resources of the journal (no-op by default)."""


class InMemoryIdempotencyJournal(IdempotencyJournal):
    """Journal of a single process — used by the tests and as a fallback."""

    def __init__(self) -> None:
        self._entries: dict[str, dict[str, Any]] = {}
        self._lock = asyncio.Lock()

    async def record_once(self, key: str, payload: dict[str, Any] | None = None) -> bool:
        async with self._lock:
            if key in self._entries:
                return False
            self._entries[key] = payload or {}
            return True

    async def contains(self, key: str) -> bool:
        async with self._lock:
            return key in self._entries

    async def entries(self) -> dict[str, dict[str, Any]]:
        async with self._lock:
            return dict(self._entries)

    async def clear(self) -> None:
        async with self._lock:
            self._entries.clear()


class RedisIdempotencyJournal(IdempotencyJournal):
    """Journal shared by every replica of the service.

    The registration uses ``SET key value NX EX ttl``: the ``NX`` flag makes
    the write atomic, therefore exactly one of the concurrently resumed
    threads observes ``True``.
    """

    def __init__(
        self,
        *,
        url: str,
        ttl_seconds: int = 7 * 24 * 3600,
        client: Any = None,
    ) -> None:
        self._url = url
        self._ttl_seconds = ttl_seconds
        self._client = client
        self._owns_client = client is None

    async def _get_client(self) -> Any:
        if self._client is None:
            from redis.asyncio import from_url

            self._client = from_url(self._url, decode_responses=True)
        return self._client

    async def close(self) -> None:
        if self._client is not None and self._owns_client:
            await self._client.aclose()
            self._client = None

    async def record_once(self, key: str, payload: dict[str, Any] | None = None) -> bool:
        client = await self._get_client()
        value = json.dumps(payload or {}, ensure_ascii=False, default=str)
        created = await client.set(
            f"{JOURNAL_KEY_PREFIX}{key}", value, nx=True, ex=self._ttl_seconds
        )
        return bool(created)

    async def contains(self, key: str) -> bool:
        client = await self._get_client()
        return bool(await client.exists(f"{JOURNAL_KEY_PREFIX}{key}"))


_DEFAULT_JOURNAL = InMemoryIdempotencyJournal()


def default_journal() -> IdempotencyJournal:
    return _DEFAULT_JOURNAL


@dataclass
class AgentExecutionContext:
    """Per invocation context shared by all nodes of one graph run."""

    invocation_id: int
    thread_id: str
    agent_type: str
    trace_id: str
    message_id: str
    limits: ExecutionLimits
    #: The span of the producer, the spans of this service become its children.
    parent_span_id: str | None = None
    input: dict[str, Any] = field(default_factory=dict)
    llm: Any = None
    github: Any = None
    journal: IdempotencyJournal = field(default_factory=default_journal)
    counters: ExecutionCounters = field(default_factory=ExecutionCounters)
    workflow_execution_id: int | None = None
    node_execution_id: int | None = None
    attempt_no: int | None = None
    correlation_id: str | None = None

    # -- statistics ------------------------------------------------------
    async def call_tool(self, operation: Callable[[], Any | Awaitable[Any]]) -> Any:
        """Run a tool operation and count it (call and, on error, failure)."""
        self.counters.record_tool_call()
        try:
            result = operation()
            if inspect.isawaitable(result):
                result = await result
            return result
        except Exception:
            self.counters.record_tool_failure()
            raise

    # -- budget enforcement -------------------------------------------------
    def check_iterations(self, iterations: int) -> None:
        if iterations > self.limits.max_iterations:
            raise BudgetExceededError(
                f"maxIterations exceeded ({iterations} > {self.limits.max_iterations})",
                diagnostics={"limit": "maxIterations", "used": iterations},
            )

    def check_tool_calls(self, tool_calls: int) -> None:
        if tool_calls > self.limits.max_tool_calls:
            raise BudgetExceededError(
                f"maxToolCalls exceeded ({tool_calls} > {self.limits.max_tool_calls})",
                diagnostics={"limit": "maxToolCalls", "used": tool_calls},
            )

    def check_tokens(self, tokens: int) -> None:
        if tokens > self.limits.max_tokens:
            raise BudgetExceededError(
                f"maxTokens exceeded ({tokens} > {self.limits.max_tokens})",
                diagnostics={"limit": "maxTokens", "used": tokens},
            )

    def consume_tool_call(self, tool_calls: int) -> int:
        """Register one tool call and enforce the tool call budget."""
        used = tool_calls + 1
        self.check_tool_calls(used)
        return used
