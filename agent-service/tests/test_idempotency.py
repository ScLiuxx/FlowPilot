"""Idempotency of AgentExecute and AgentResume.

* ``AgentExecute``  -> idempotent on ``invocationId``
* ``AgentResume``   -> idempotent on ``invocationId`` + ``resumeRequestId``

A redelivered message must never start a second agent run and must return the
previously recorded terminal result.
"""

from __future__ import annotations

from app.messaging.events import AgentExecutePayload, AgentResumePayload
from app.persistence.repository import STATUS_SUCCEEDED
from tests.conftest import make_execute_envelope, make_resume_envelope


async def test_redelivered_execute_does_not_start_a_second_run(executor, store, github_tool):
    envelope = make_execute_envelope()
    payload = AgentExecutePayload.model_validate(envelope.payload)

    first = await executor.execute(envelope, payload)
    calls_after_first = len(github_tool.calls)

    second = await executor.execute(envelope, payload)

    assert first.status == "COMPLETED"
    assert second.duplicate is True
    assert second.payload == first.payload
    # the graph was not executed a second time
    assert len(github_tool.calls) == calls_after_first
    assert first.payload["invocationId"] == 30001


async def test_redelivery_with_a_different_message_id_is_still_idempotent(
    executor, store, github_tool
):
    """The idempotency key is the business id, not the message id."""
    first_envelope = make_execute_envelope(message_id=None)
    second_envelope = make_execute_envelope(message_id=None)

    first = await executor.execute(
        first_envelope, AgentExecutePayload.model_validate(first_envelope.payload)
    )
    calls = len(github_tool.calls)
    second = await executor.execute(
        second_envelope, AgentExecutePayload.model_validate(second_envelope.payload)
    )

    assert second.duplicate is True
    assert second.payload == first.payload
    assert len(github_tool.calls) == calls


async def test_the_record_is_unique_per_invocation(executor, store):
    envelope = make_execute_envelope()
    payload = AgentExecutePayload.model_validate(envelope.payload)
    await executor.execute(envelope, payload)

    record = await store.get(30001)
    assert record is not None
    assert record.status == STATUS_SUCCEEDED
    assert record.thread_id == "flowpilot-30001"
    assert record.checkpoint_id is not None


async def test_the_thread_id_is_never_regenerated(executor, store, checkpointer):
    envelope = make_execute_envelope()
    payload = AgentExecutePayload.model_validate(envelope.payload)
    await executor.execute(envelope, payload)
    await executor.execute(envelope, payload)

    record = await store.get(30001)
    assert record.thread_id == "flowpilot-30001"

    from app.persistence.checkpoint import list_checkpoints

    checkpoints = await list_checkpoints(checkpointer, "flowpilot-30001")
    assert checkpoints, "the checkpoint of the invocation must exist"
    other_threads = await list_checkpoints(checkpointer, "flowpilot-99999")
    assert other_threads == []


async def test_an_unknown_thread_id_is_rejected(executor, store):
    envelope = make_execute_envelope(thread_id="flowpilot-99999")
    payload = AgentExecutePayload.model_validate(envelope.payload)

    outcome = await executor.execute(envelope, payload)

    assert outcome.status == "FAILED"
    assert outcome.payload["errorCode"] == "AG-THREAD-ID-001"
    assert outcome.payload["errorCategory"] == "VALIDATION"
    assert outcome.payload["retryable"] is False


async def test_a_redelivered_resume_does_not_resume_twice(executor, store, journal):
    """The resume is idempotent on invocationId + resumeRequestId."""
    execute_envelope = make_execute_envelope(agent_type="REPORT_REVIEWER")
    execute_envelope = execute_envelope.model_copy(
        update={
            "payload": {
                **execute_envelope.payload,
                "input": {"repository": "acme/widget", "analysis": {"riskLevel": "LOW"}},
            }
        }
    )
    await executor.execute(
        execute_envelope, AgentExecutePayload.model_validate(execute_envelope.payload)
    )
    record = await store.get(30001)
    assert record is not None and record.status == "INTERRUPTED"

    resume_envelope = make_resume_envelope()
    resume_payload = AgentResumePayload.model_validate(resume_envelope.payload)

    first = await executor.resume(resume_envelope, resume_payload)
    second = await executor.resume(resume_envelope, resume_payload)

    assert first.status == "COMPLETED"
    assert second.duplicate is True
    assert second.payload == first.payload

    # the side effect in front of the interrupt happened exactly once
    approval_entries = [
        key for key in journal.entries() if key.startswith("approval-request:")
    ]
    assert len(approval_entries) == 1


async def test_a_resume_with_an_unknown_invocation_is_rejected(executor, store):
    resume_envelope = make_resume_envelope(invocation_id=424242)
    resume_payload = AgentResumePayload.model_validate(resume_envelope.payload)

    outcome = await executor.resume(resume_envelope, resume_payload)

    assert outcome.status == "FAILED"
    assert outcome.payload["errorCode"] == "AG-INVOCATION-001"


async def test_a_retry_after_a_failure_runs_on_the_same_thread(executor, store, registry):
    """A higher attempt number is a legitimate retry, not a redelivery."""
    envelope = make_execute_envelope(attempt_no=1)
    payload = AgentExecutePayload.model_validate(envelope.payload)

    # simulate a failed first attempt
    await store.create(
        invocation_id=30001,
        thread_id="flowpilot-30001",
        agent_type="REPOSITORY_ANALYST",
        status="FAILED",
        attempt_no=1,
    )
    retry_envelope = envelope.model_copy(update={"attemptNo": 2})
    outcome = await executor.execute(retry_envelope, payload)

    assert outcome.status == "COMPLETED"
    record = await store.get(30001)
    assert record.thread_id == "flowpilot-30001"
    assert record.attempt_no == 2
