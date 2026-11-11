"""Shared LangGraph state for every agent graph.

The state is the only thing that is persisted by the checkpointer, therefore
it must stay JSON serialisable. Counters (tool calls, tokens, iterations) live
in the state so that they survive an interrupt / resume cycle.
"""

from __future__ import annotations

from typing import Any, TypedDict


class AgentState(TypedDict, total=False):
    """State shared by the research, analysis and review graphs."""

    # --- inputs -------------------------------------------------------
    input: dict[str, Any]
    repository: str
    agentType: str

    # --- collected evidence -------------------------------------------
    facts: dict[str, Any]

    # --- model output ---------------------------------------------------
    analysis: dict[str, Any]

    # --- human in the loop ------------------------------------------------
    review: dict[str, Any]
    interruptData: dict[str, Any]

    # --- produced artefact -------------------------------------------------
    report: dict[str, Any]

    # --- budget counters ----------------------------------------------------
    iterations: int
    toolCalls: int
    llmCalls: int
    inputTokens: int
    outputTokens: int
