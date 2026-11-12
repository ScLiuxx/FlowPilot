"""The recovery of a resume whose worker died.

The bug this file guards against: a worker that crashed while it continued a
paused thread left the resume ledger in the ``PROCESSING`` state. Every later
delivery of the resume command saw the record, returned without continuing the
thread and acknowledged the message — nobody ever resumed the agent again.

The fix is a lease on the ledger: a worker that dies leaves an expired lease
behind, and the next delivery takes the lease over and continues the *same*
thread and checkpoint.
"""

from __future__ import annotations

from datetime import timedelta

from app.messaging.events import AgentExecutePayload, AgentResumePayload
from app.persistence.repository import (
    RESUME_CLAIMED,
    STATUS_SUCCEEDED,
    InMemoryExecutionRecordStore,
)
from app.runtime.executor import _utcnow
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


async def _interrupt_the_agent(executor, invocation_id: int = 30001):
    envelope = _review_envelope(invocation_id=invocation_id)
    outcome = await executor.execute(
        envelope, AgentExecutePayload.model_validate(envelope.payload)
    )
    assert outcome.status == "INTERRUPTED"
    return envelope


async def test_a_resume_whose_worker_died_is_taken_over(executor, store, settings):
    """The dead worker left the ledger behind — the new worker continues it."""
    await _interrupt_the_agent(executor)

    # The worker died right after it claimed the resume: the ledger is in
    # flight and the lease is expired.
    record, created = await store.create_resume(
        invocation_id=30001,
        resume_request_id="resume-001",
        thread_id="flowpilot-30001",
        status=RESUME_CLAIMED,
        payload={},
        lease_expire_at=_utcnow() - timedelta(seconds=1),
    )
    assert created is True
    assert record.lease_token == 1

    resume_envelope = make_resume_envelope()
    outcome = await executor.resume(
        resume_envelope, AgentResumePayload.model_validate(resume_envelope.payload)
    )

    assert outcome.status == "COMPLETED"
    assert outcome.message_type.value == "AgentCompleted"

    ledger = await store.get_resume(30001, "resume-001")
    assert ledger is not None
    # The takeover is visible in the ledger.
    assert ledger.takeover_count == 1
    assert ledger.lease_token == 2
    assert ledger.status == "COMPLETED"

    invocation = await store.get(30001)
    assert invocation.status == STATUS_SUCCEEDED


async def test_a_live_lease_is_not_taken_over(executor, store, settings):
    """A worker that is still working keeps its lease."""
    await _interrupt_the_agent(executor)

    record, created = await store.create_resume(
        invocation_id=30001,
        resume_request_id="resume-001",
        thread_id="flowpilot-30001",
        status=RESUME_CLAIMED,
        payload={},
        lease_expire_at=_utcnow() + timedelta(seconds=300),
    )
    assert created is True

    # The owner of the lease is still alive: the delivery must not continue
    # the thread a second time.
    settings.resume_lease_wait_seconds = 0
    settings.resume_lease_seconds = 0
    resume_envelope = make_resume_envelope()
    outcome = await executor.resume(
        resume_envelope, AgentResumePayload.model_validate(resume_envelope.payload)
    )

    assert outcome.duplicate is True
    assert outcome.publish is False

    ledger = await store.get_resume(30001, "resume-001")
    assert ledger is not None
    assert ledger.takeover_count == 0
    assert ledger.lease_token == 1


async def test_the_takeover_is_limited(executor, store, settings):
    """A resume that keeps killing its worker is given up."""
    await _interrupt_the_agent(executor)

    settings.resume_max_takeovers = 2
    for _ in range(2):
        record, _ = await store.create_resume(
            invocation_id=30001,
            resume_request_id="resume-001",
            thread_id="flowpilot-30001",
            status=RESUME_CLAIMED,
            payload={},
            lease_expire_at=_utcnow() - timedelta(seconds=1),
        )
        # The lease of the previous owner expired, the takeover bumps the token.
        await store.claim_resume_lease(
            30001,
            "resume-001",
            lease_expire_at=_utcnow() - timedelta(seconds=1),
            expected_lease_token=record.lease_token,
        )

    ledger = await store.get_resume(30001, "resume-001")
    assert ledger is not None and ledger.takeover_count == 2

    resume_envelope = make_resume_envelope()
    outcome = await executor.resume(
        resume_envelope, AgentResumePayload.model_validate(resume_envelope.payload)
    )

    assert outcome.status == "FAILED"
    assert outcome.payload["errorCode"] == "AG-RESUME-TOKEOVER-LIMIT-001"
    assert outcome.payload["retryable"] is True


async def test_a_worker_that_lost_the_lease_publishes_nothing(executor, store):
    """The fencing token keeps the old worker from writing the result."""
    await _interrupt_the_agent(executor)

    # The first worker claimed the resume and is running.
    record, _ = await store.create_resume(
        invocation_id=30001,
        resume_request_id="resume-001",
        thread_id="flowpilot-30001",
        status=RESUME_CLAIMED,
        payload={},
        lease_expire_at=_utcnow() - timedelta(seconds=1),
    )

    # Meanwhile the lease expired and another worker took it over.
    claimed = await store.claim_resume_lease(
        30001,
        "resume-001",
        lease_expire_at=_utcnow() + timedelta(seconds=60),
        expected_lease_token=record.lease_token,
    )
    assert claimed is not None
    assert claimed.lease_token == record.lease_token + 1

    # The old worker tries to close the ledger with its (stale) token.
    updated = await store.complete_resume(
        30001,
        "resume-001",
        lease_token=record.lease_token,
        status="COMPLETED",
        payload={"messageType": "AgentCompleted", "payload": {}},
    )
    assert updated is None, "a stale lease token must not close the ledger"

    ledger = await store.get_resume(30001, "resume-001")
    assert ledger is not None
    assert ledger.status == RESUME_CLAIMED


async def test_the_ledger_is_closed_in_the_same_transaction_as_the_result(
    executor, store
):
    """The result event and the ledger belong together."""
    await _interrupt_the_agent(executor)

    resume_envelope = make_resume_envelope()
    outcome = await executor.resume(
        resume_envelope, AgentResumePayload.model_validate(resume_envelope.payload)
    )
    assert outcome.status == "COMPLETED"

    ledger = await store.get_resume(30001, "resume-001")
    assert ledger is not None
    assert ledger.status == "COMPLETED"
    assert ledger.payload["messageType"] == "AgentCompleted"
    # The lease is released, the request is not in flight anymore.
    assert ledger.lease_expire_at is None

    outbox = await store.list_outbox()
    assert "AgentCompleted" in [row.message_type for row in outbox]


async def test_the_lease_of_a_running_resume_is_renewed(executor, store, settings):
    """A long running resume keeps its lease."""
    await _interrupt_the_agent(executor)

    settings.resume_lease_seconds = 3
    resume_envelope = make_resume_envelope()
    outcome = await executor.resume(
        resume_envelope, AgentResumePayload.model_validate(resume_envelope.payload)
    )
    assert outcome.status == "COMPLETED"

    ledger = await store.get_resume(30001, "resume-001")
    assert ledger is not None
    assert ledger.lease_expire_at is None


async def test_the_store_claims_the_lease_only_once(store: InMemoryExecutionRecordStore):
    """The compare and set of the lease token keeps two workers apart."""
    await store.create(
        invocation_id=30001,
        thread_id="flowpilot-30001",
        agent_type="REPOSITORY_ANALYST",
    )
    record, created = await store.create_resume(
        invocation_id=30001,
        resume_request_id="resume-001",
        thread_id="flowpilot-30001",
        status=RESUME_CLAIMED,
        payload={},
        lease_expire_at=_utcnow() - timedelta(seconds=1),
    )
    assert created is True

    first = await store.claim_resume_lease(
        30001,
        "resume-001",
        lease_expire_at=_utcnow() + timedelta(seconds=60),
        expected_lease_token=record.lease_token,
    )
    assert first is not None
    assert first.lease_token == 2
    assert first.takeover_count == 1

    # The second worker uses the stale token: it must not win.
    second = await store.claim_resume_lease(
        30001,
        "resume-001",
        lease_expire_at=_utcnow() + timedelta(seconds=60),
        expected_lease_token=record.lease_token,
    )
    assert second is None
