"""The cooperative cancellation of a running agent.

The cancel is a *request*: the running graph is stopped where it can be
stopped, the side effects it already caused are of course not undone. What
must not happen is that a cancelled invocation is overwritten by the success
that arrives late — the terminal update is therefore conditional.
"""

from __future__ import annotations

import asyncio

import pytest

from app.messaging.events import AgentCancelPayload, AgentExecutePayload
from app.persistence.repository import (
    STATUS_CANCELLED,
    STATUS_SUCCEEDED,
)
from app.runtime.errors import ValidationError
from tests.conftest import make_cancel_envelope, make_execute_envelope


class SlowGitHubTool:
    """A tool that takes its time, so the cancel arrives while it runs."""

    def __init__(self) -> None:
        self.calls = 0

    async def _wait(self) -> None:
        self.calls += 1
        await asyncio.sleep(30)

    async def get_repository(self, ref: str) -> dict:
        await self._wait()
        return {"fullName": ref}

    async def list_commits(self, ref: str, per_page: int = 10) -> list[dict]:
        await self._wait()
        return []

    async def list_issues(self, ref: str, state: str = "open", per_page: int = 10):
        await self._wait()
        return []

    async def list_pull_requests(self, ref: str, state: str = "open", per_page: int = 10):
        await self._wait()
        return []

    async def list_releases(self, ref: str, per_page: int = 5) -> list[dict]:
        await self._wait()
        return []

    async def get_readme(self, ref: str) -> str:
        await self._wait()
        return "# readme"


@pytest.fixture
def slow_executor(settings, store, checkpointer, registry, journal):
    """An executor whose tool call blocks, so a cancel can arrive."""
    from app.runtime.executor import AgentExecutor

    return AgentExecutor(
        store=store,
        checkpointer=checkpointer,
        registry=registry,
        settings=settings,
        github_factory=lambda: SlowGitHubTool(),
        journal=journal,
    )


async def test_a_cancel_stops_the_running_graph(slow_executor, store):
    """The graph is stopped while it waits for the model."""
    envelope = make_execute_envelope()
    payload = AgentExecutePayload.model_validate(envelope.payload)

    running = asyncio.create_task(slow_executor.execute(envelope, payload))
    # The graph is running now.
    await asyncio.sleep(0.05)

    cancel_envelope = make_cancel_envelope()
    cancel_payload = AgentCancelPayload.model_validate(cancel_envelope.payload)
    cancelled = await slow_executor.cancel(cancel_envelope, cancel_payload)

    assert cancelled.status == "CANCELLED"
    assert cancelled.payload["errorCode"] == "AG-CANCELED-001"

    outcome = await running
    # The run did not report a success after the cancel.
    assert outcome.status != "COMPLETED"
    assert outcome.superseded is True

    record = await store.get(30001)
    assert record is not None
    assert record.status == STATUS_CANCELLED


async def test_a_cancel_does_not_overwrite_a_finished_run(executor, store):
    """A cancel that arrives after the success is ignored."""
    envelope = make_execute_envelope()
    payload = AgentExecutePayload.model_validate(envelope.payload)
    outcome = await executor.execute(envelope, payload)
    assert outcome.status == "COMPLETED"

    cancel_envelope = make_cancel_envelope()
    cancel_payload = AgentCancelPayload.model_validate(cancel_envelope.payload)
    cancelled = await executor.cancel(cancel_envelope, cancel_payload)

    assert cancelled.superseded is True

    record = await store.get(30001)
    assert record is not None
    assert record.status == STATUS_SUCCEEDED


async def test_a_cancel_of_an_unknown_invocation_is_rejected(executor, store):
    """The cancel of an unknown invocation is a validation error."""
    cancel_envelope = make_cancel_envelope(invocation_id=424242)
    cancel_payload = AgentCancelPayload.model_validate(cancel_envelope.payload)

    with pytest.raises(ValidationError) as excinfo:
        await executor.cancel(cancel_envelope, cancel_payload)
    assert excinfo.value.error_code == "AG-INVOCATION-001"


async def test_the_cancel_is_published_to_the_backend(slow_executor, store):
    """The backend learns about the cancel."""
    envelope = make_execute_envelope()
    payload = AgentExecutePayload.model_validate(envelope.payload)
    running = asyncio.create_task(slow_executor.execute(envelope, payload))
    await asyncio.sleep(0.05)

    cancel_envelope = make_cancel_envelope()
    cancel_payload = AgentCancelPayload.model_validate(cancel_envelope.payload)
    await slow_executor.cancel(cancel_envelope, cancel_payload)
    await running

    outbox = await store.list_outbox()
    messages = [row.message_type for row in outbox]
    assert "AgentFailed" in messages
    # The success of the cancelled run was not published.
    assert "AgentCompleted" not in messages
