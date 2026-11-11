"""Interrupt / resume (human in the loop).

The review graph suspends with LangGraph's ``interrupt()``. The agent service
publishes ``AgentInterrupted``; the Java backend asks the user and sends the
decision back as ``AgentResume``. The graph is then continued with
``Command(resume=...)`` on the **same** thread.

LangGraph re-executes the node that raised the interrupt when the thread is
resumed. Every side effect in front of the interrupt must therefore be
idempotent — the test below proves that the effect happened exactly once.
"""

from __future__ import annotations

from app.messaging.events import AgentExecutePayload, AgentResumePayload
from app.persistence.repository import STATUS_INTERRUPTED, STATUS_SUCCEEDED
from tests.conftest import make_execute_envelope, make_resume_envelope


def _review_envelope(invocation_id: int = 30001, **kwargs):
    envelope = make_execute_envelope(
        invocation_id=invocation_id, agent_type="REPORT_REVIEWER", **kwargs
    )
    payload = {
        **envelope.payload,
        "input": {
            "repository": "acme/widget",
            "analysis": {
                "architectureAnalysis": "layered",
                "maintenanceAnalysis": "active",
                "riskAnalysis": "low",
                "recommendation": "continue",
                "riskLevel": "LOW",
            },
        },
    }
    return envelope.model_copy(update={"payload": payload})


async def test_the_graph_interrupts_and_waits_for_a_human(executor, store):
    envelope = _review_envelope()
    outcome = await executor.execute(
        envelope, AgentExecutePayload.model_validate(envelope.payload)
    )

    assert outcome.status == "INTERRUPTED"
    assert outcome.message_type.value == "AgentInterrupted"
    assert outcome.payload["interruptType"] == "HUMAN_APPROVAL"
    assert outcome.payload["threadId"] == "flowpilot-30001"
    assert outcome.payload["checkpointId"]
    assert outcome.payload["interruptData"]["requestedAction"] == "APPROVE_REPORT"

    record = await store.get(30001)
    assert record is not None
    assert record.status == STATUS_INTERRUPTED
    assert record.checkpoint_id == outcome.payload["checkpointId"]


async def test_the_resume_continues_on_the_same_thread(executor, store, checkpointer):
    envelope = _review_envelope()
    await executor.execute(
        envelope, AgentExecutePayload.model_validate(envelope.payload)
    )
    interrupted_checkpoint = (await store.get(30001)).checkpoint_id

    resume_envelope = make_resume_envelope()
    outcome = await executor.resume(
        resume_envelope, AgentResumePayload.model_validate(resume_envelope.payload)
    )

    assert outcome.status == "COMPLETED"
    assert outcome.message_type.value == "AgentCompleted"
    assert outcome.payload["threadId"] == "flowpilot-30001"
    assert outcome.payload["checkpointId"] != interrupted_checkpoint

    record = await store.get(30001)
    assert record.status == STATUS_SUCCEEDED

    from app.persistence.checkpoint import list_checkpoints

    checkpoints = await list_checkpoints(checkpointer, "flowpilot-30001")
    assert len(checkpoints) >= 2
    assert await list_checkpoints(checkpointer, "flowpilot-99999") == []


async def test_the_side_effect_in_front_of_the_interrupt_is_idempotent(
    executor, store, journal
):
    """LangGraph re-executes the interrupted node — the effect happens once."""
    envelope = _review_envelope()
    await executor.execute(
        envelope, AgentExecutePayload.model_validate(envelope.payload)
    )
    resume_envelope = make_resume_envelope()
    await executor.resume(
        resume_envelope, AgentResumePayload.model_validate(resume_envelope.payload)
    )

    approval_entries = [key for key in journal.entries() if key.startswith("approval-request:")]
    assert len(approval_entries) == 1, "the side effect must not be repeated"


async def test_the_review_decision_reaches_the_report(executor, store):
    envelope = _review_envelope()
    await executor.execute(
        envelope, AgentExecutePayload.model_validate(envelope.payload)
    )
    resume_envelope = make_resume_envelope(
        resume_value={"approved": False, "comment": "needs more details"}
    )
    outcome = await executor.resume(
        resume_envelope, AgentResumePayload.model_validate(resume_envelope.payload)
    )

    report = outcome.payload["result"]["report"]
    assert report["status"] == "REJECTED"
    assert report["review"]["approved"] is False
    assert report["review"]["comment"] == "needs more details"


async def test_the_research_graph_can_require_a_human_review(executor, store):
    envelope = make_execute_envelope()
    payload = {
        **envelope.payload,
        "input": {"repository": "acme/widget", "requireHumanReview": True},
    }
    envelope = envelope.model_copy(update={"payload": payload})

    outcome = await executor.execute(
        envelope, AgentExecutePayload.model_validate(envelope.payload)
    )
    assert outcome.status == "INTERRUPTED"

    resume_envelope = make_resume_envelope()
    resumed = await executor.resume(
        resume_envelope, AgentResumePayload.model_validate(resume_envelope.payload)
    )
    assert resumed.status == "COMPLETED"
    assert resumed.payload["result"]["review"]["approved"] is True
