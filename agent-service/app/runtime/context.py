"""Execution context handed to every graph node.

The context is injected into the LangGraph nodes through the runtime context
(``Runtime.context``). It carries the immutable invocation metadata, the
configured budgets and the collaborators (LLM, GitHub tool) a node may use.

It is deliberately *not* part of the checkpointed graph state: only the plain
counters in :class:`app.runtime.state.AgentState` are persisted, so that a
thread can be resumed by a different process.
"""

from __future__ import annotations

import threading
from dataclasses import dataclass, field
from typing import Any

from app.runtime.errors import BudgetExceededError


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


class IdempotencyJournal:
    """Process wide journal of already performed side effects.

    LangGraph re-executes the node that raised an ``interrupt()`` when the
    thread is resumed. Every side effect performed *before* the interrupt must
    therefore be idempotent. Instead of relying on the side effect itself being
    idempotent, the node registers the effect in this journal first: the
    journal is keyed, so the second execution of the node observes the key and
    skips the effect.
    """

    def __init__(self) -> None:
        self._entries: dict[str, dict[str, Any]] = {}
        self._lock = threading.Lock()

    def record_once(self, key: str, payload: dict[str, Any] | None = None) -> bool:
        """Register ``key``. Returns ``True`` only for the first registration."""
        with self._lock:
            if key in self._entries:
                return False
            self._entries[key] = payload or {}
            return True

    def contains(self, key: str) -> bool:
        with self._lock:
            return key in self._entries

    def entries(self) -> dict[str, dict[str, Any]]:
        with self._lock:
            return dict(self._entries)

    def clear(self) -> None:
        with self._lock:
            self._entries.clear()


_DEFAULT_JOURNAL = IdempotencyJournal()


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
    input: dict[str, Any] = field(default_factory=dict)
    llm: Any = None
    github: Any = None
    journal: IdempotencyJournal = field(default_factory=default_journal)
    workflow_execution_id: int | None = None
    node_execution_id: int | None = None
    attempt_no: int | None = None
    correlation_id: str | None = None

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
