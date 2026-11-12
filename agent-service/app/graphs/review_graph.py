"""REPORT_REVIEWER graph — the human-in-the-loop agent.

    draft  ->  approval  ->  finalize  ->  END

``approval`` raises LangGraph's ``interrupt()``. The agent service then
publishes ``AgentInterrupted`` and the Java backend asks the user for a
decision. The decision travels back as ``AgentResume`` and the *same* thread
is continued with ``Command(resume=...)``.

LangGraph re-executes the node that raised the interrupt when the thread is
resumed (see the architecture design, chapter 10.3). Every side effect that
happens *before* ``interrupt()`` must therefore be idempotent:

* the review id is a pure function of the invocation id and the draft, and
* the "approval request created" side effect is registered in a keyed journal,
  so the second execution of the node observes the key and does not repeat it.

No non repeatable work (mail, notifications, external writes) happens before
the interrupt — creating the approval record is the responsibility of the
Java backend, which does it when it receives ``AgentInterrupted``.
"""

from __future__ import annotations

import hashlib
import json
from typing import Any

from langgraph.graph import END, START, StateGraph
from langgraph.runtime import Runtime
from langgraph.types import interrupt

from app.runtime.context import AgentExecutionContext
from app.runtime.state import AgentState
from app.tools.report_tool import build_report

AGENT_TYPE_REPORT_REVIEWER = "REPORT_REVIEWER"


def _review_id(invocation_id: int, draft: dict[str, Any]) -> str:
    digest = hashlib.sha256(
        json.dumps(draft, sort_keys=True, ensure_ascii=False).encode("utf-8")
    ).hexdigest()[:16]
    return f"review-{invocation_id}-{digest}"


async def draft_node(state: AgentState, runtime: Runtime) -> dict[str, Any]:
    context: AgentExecutionContext = runtime.context
    iterations = int(state.get("iterations", 0)) + 1
    context.check_iterations(iterations)

    payload = state.get("input") or {}
    analysis = payload.get("analysis") if isinstance(payload.get("analysis"), dict) else {}
    repository_value = payload.get("repository")
    if isinstance(repository_value, dict):
        repository = str(repository_value.get("fullName") or "unknown/unknown")
    else:
        repository = str(
            repository_value or payload.get("repositoryUrl") or "unknown/unknown"
        )
    report = build_report(
        repository=repository,
        analysis=analysis,
        facts=payload.get("facts") if isinstance(payload.get("facts"), dict) else {},
        report_type="REPORT_REVIEW",
    )
    return {"repository": repository, "report": report, "iterations": iterations}


async def approval_node(state: AgentState, runtime: Runtime) -> dict[str, Any]:
    """Suspend the agent until a human approves the draft report."""
    context: AgentExecutionContext = runtime.context
    report = state.get("report") or {}
    review_id = _review_id(context.invocation_id, report)

    # --- side effect before the interrupt -------------------------------
    # Must be idempotent: LangGraph re-executes this node on resume. The
    # journal is keyed by the review id, so the effect is recorded exactly
    # once even though the node body runs twice.
    await context.journal.record_once(
        f"approval-request:{review_id}",
        {"invocationId": context.invocation_id, "reviewId": review_id},
    )

    decision = interrupt(
        {
            "reviewId": review_id,
            "title": "请审核报告",
            "description": "Agent 已生成报告草稿，需要人工确认后继续。",
            "requestedAction": "APPROVE_REPORT",
            "payload": {
                "invocationId": context.invocation_id,
                "threadId": context.thread_id,
                "reportTitle": report.get("title"),
            },
        }
    )
    payload = decision if isinstance(decision, dict) else {}
    return {
        "review": {
            "reviewId": review_id,
            "approved": bool(payload.get("approved")),
            "comment": str(payload.get("comment") or ""),
        }
    }


async def finalize_node(state: AgentState, runtime: Runtime) -> dict[str, Any]:
    context: AgentExecutionContext = runtime.context
    iterations = int(state.get("iterations", 0)) + 1
    context.check_iterations(iterations)
    await context.check_cancelled()

    review = state.get("review") or {}
    report = dict(state.get("report") or {})
    report["review"] = review
    report["status"] = "APPROVED" if review.get("approved") else "REJECTED"
    report["markdown"] = _render(report)
    return {"report": report, "iterations": iterations}


def _render(report: dict[str, Any]) -> str:
    from app.tools.report_tool import render_markdown

    return render_markdown(report)


def build_review_graph(context: AgentExecutionContext) -> StateGraph:
    """Build the REPORT_REVIEWER graph."""
    graph = StateGraph(AgentState, context_schema=AgentExecutionContext)
    graph.add_node("draft", draft_node)
    graph.add_node("approval", approval_node)
    graph.add_node("finalize", finalize_node)

    graph.add_edge(START, "draft")
    graph.add_edge("draft", "approval")
    graph.add_edge("approval", "finalize")
    graph.add_edge("finalize", END)
    return graph
