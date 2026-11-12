"""The recovery scanner of the resume requests.

The scanner walks the ledger on its own and re-publishes the resume commands
whose worker did not come back. Without it the recovery depends on the broker
redelivering the command: a worker that loses its connection to the broker but
keeps running holds the lease, a duplicate delivery waits for it and gives up,
and the consumer acknowledges that message. When the worker finally dies,
nothing is left in the broker to trigger the takeover.

The scanner therefore does not depend on the broker at all: it finds the stale
requests itself and publishes their command again.
"""

from __future__ import annotations

from datetime import timedelta

from app.messaging.events import MessageType
from app.persistence.repository import RESUME_CLAIMED, STATUS_INTERRUPTED
from app.runtime.executor import _utcnow
from app.runtime.resume_recovery import ResumeRecoveryScanner
from tests.conftest import make_resume_envelope


class FakePublisher:
    """A publisher that records the commands instead of sending them."""

    def __init__(self) -> None:
        self.connected = True
        self.published: list[tuple[object, str]] = []

    async def publish(self, envelope, routing_key: str):
        self.published.append((envelope, routing_key))
        return envelope


async def _interrupt_the_agent(executor) -> None:
    """Let the agent interrupt itself, so that the thread has a checkpoint."""
    from app.messaging.events import AgentExecutePayload

    envelope = make_resume_envelope()
    payload = dict(envelope.payload)
    payload["agentType"] = "REPORT_REVIEWER"
    execute_envelope = envelope.model_copy(update={"payload": payload})
    outcome = await executor.execute(
        execute_envelope, AgentExecutePayload.model_validate(payload)
    )
    assert outcome.status == "INTERRUPTED"


def _scanner(store, checkpointer, publisher, settings) -> ResumeRecoveryScanner:
    return ResumeRecoveryScanner(
        store=store,
        checkpointer=checkpointer,
        publisher=publisher,
        settings=settings,
    )


async def test_a_stale_resume_is_published_again(executor, store, checkpointer, settings):
    """The command of a request whose worker died is published again."""
    await _interrupt_the_agent(executor)

    publisher = FakePublisher()
    scanner = _scanner(store, checkpointer, publisher, settings)

    # The worker claimed the resume and died: the lease expired.
    await store.create_resume(
        invocation_id=30001,
        resume_request_id="resume-001",
        thread_id="flowpilot-30001",
        status=RESUME_CLAIMED,
        payload={
            "workflowExecutionId": 777,
            "nodeExecutionId": 888,
            "attemptNo": 1,
            "traceId": "a" * 32,
            "approvalId": 40001,
            "resumeValue": {"approved": True},
        },
        lease_expire_at=_utcnow() - timedelta(seconds=120),
    )

    recovered = await scanner.scan_once()

    assert recovered == 1
    assert len(publisher.published) == 1
    envelope, routing_key = publisher.published[0]
    assert routing_key == "agent.resume"
    assert envelope.messageType == MessageType.AGENT_RESUME
    # The idempotency key is the one of the original request, so the consumer
    # finds the ledger and continues the thread instead of starting a second run.
    assert envelope.payload["resumeRequestId"] == "resume-001"
    assert envelope.payload["invocationId"] == 30001
    assert envelope.payload["threadId"] == "flowpilot-30001"
    assert envelope.payload["approvalId"] == 40001
    assert envelope.payload["resumeValue"] == {"approved": True}
    # The routing of the original command is restored.
    assert envelope.workflowExecutionId == 777
    assert envelope.nodeExecutionId == 888


async def test_a_live_lease_is_not_published_again(executor, store, checkpointer, settings):
    """A request whose owner is still working is left alone."""
    await _interrupt_the_agent(executor)

    publisher = FakePublisher()
    scanner = _scanner(store, checkpointer, publisher, settings)

    await store.create_resume(
        invocation_id=30001,
        resume_request_id="resume-001",
        thread_id="flowpilot-30001",
        status=RESUME_CLAIMED,
        payload={},
        lease_expire_at=_utcnow() + timedelta(seconds=120),
    )

    recovered = await scanner.scan_once()

    assert recovered == 0
    assert publisher.published == []


async def test_a_finished_resume_is_not_published_again(executor, store, checkpointer, settings):
    """A request that already reached its result is left alone."""
    await _interrupt_the_agent(executor)

    publisher = FakePublisher()
    scanner = _scanner(store, checkpointer, publisher, settings)

    await store.create_resume(
        invocation_id=30001,
        resume_request_id="resume-001",
        thread_id="flowpilot-30001",
        status=RESUME_CLAIMED,
        payload={},
        lease_expire_at=_utcnow() - timedelta(seconds=120),
    )
    # The invocation already succeeded: the ledger was closed by the result.
    await store.update(30001, status="SUCCEEDED")

    recovered = await scanner.scan_once()

    assert recovered == 0
    assert publisher.published == []


async def test_a_resume_without_a_checkpoint_is_not_published(
    executor, store, checkpointer, settings
):
    """A request whose thread has no checkpoint cannot be continued."""
    publisher = FakePublisher()
    scanner = _scanner(store, checkpointer, publisher, settings)

    await store.create(
        invocation_id=30001,
        thread_id="flowpilot-30001",
        agent_type="REPOSITORY_ANALYST",
    )
    await store.update(30001, status=STATUS_INTERRUPTED)
    await store.create_resume(
        invocation_id=30001,
        resume_request_id="resume-001",
        thread_id="flowpilot-30001",
        status=RESUME_CLAIMED,
        payload={},
        lease_expire_at=_utcnow() - timedelta(seconds=120),
    )

    recovered = await scanner.scan_once()

    assert recovered == 0
    assert publisher.published == []


async def test_a_request_is_not_published_twice_in_a_row(
    executor, store, checkpointer, settings
):
    """The command of a request is not published over and over again."""
    await _interrupt_the_agent(executor)

    publisher = FakePublisher()
    scanner = _scanner(store, checkpointer, publisher, settings)

    await store.create_resume(
        invocation_id=30001,
        resume_request_id="resume-001",
        thread_id="flowpilot-30001",
        status=RESUME_CLAIMED,
        payload={},
        lease_expire_at=_utcnow() - timedelta(seconds=120),
    )

    assert await scanner.scan_once() == 1
    # The command is probably still on its way to a consumer.
    assert await scanner.scan_once() == 0
    assert len(publisher.published) == 1


async def test_nothing_is_published_without_a_broker(executor, store, checkpointer, settings):
    """Without a broker there is nothing to publish — and nothing is lost."""
    await _interrupt_the_agent(executor)

    publisher = FakePublisher()
    publisher.connected = False
    scanner = _scanner(store, checkpointer, publisher, settings)

    await store.create_resume(
        invocation_id=30001,
        resume_request_id="resume-001",
        thread_id="flowpilot-30001",
        status=RESUME_CLAIMED,
        payload={},
        lease_expire_at=_utcnow() - timedelta(seconds=120),
    )

    assert await scanner.scan_once() == 0
    assert publisher.published == []
