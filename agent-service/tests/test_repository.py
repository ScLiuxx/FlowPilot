"""The durable execution record store."""

from __future__ import annotations

from app.persistence.repository import (
    STATUS_FAILED,
    STATUS_RUNNING,
    STATUS_SUCCEEDED,
    InMemoryExecutionRecordStore,
)


async def test_the_invocation_id_is_unique():
    store = InMemoryExecutionRecordStore()
    first, created = await store.create(
        invocation_id=30001, thread_id="flowpilot-30001", agent_type="REPOSITORY_ANALYST"
    )
    assert created is True
    assert first.status == STATUS_RUNNING

    second, created_again = await store.create(
        invocation_id=30001, thread_id="flowpilot-30001", agent_type="REPOSITORY_ANALYST"
    )
    assert created_again is False
    assert second.invocation_id == first.invocation_id


async def test_the_terminal_result_is_persisted():
    store = InMemoryExecutionRecordStore()
    await store.create(
        invocation_id=30001, thread_id="flowpilot-30001", agent_type="REPOSITORY_ANALYST"
    )
    await store.update(
        30001,
        status=STATUS_SUCCEEDED,
        checkpoint_id="checkpoint-1",
        result={"invocationId": 30001, "threadId": "flowpilot-30001"},
        usage={"inputTokens": 10, "outputTokens": 4},
    )

    record = await store.get(30001)
    assert record is not None
    assert record.status == STATUS_SUCCEEDED
    assert record.checkpoint_id == "checkpoint-1"
    assert record.result["threadId"] == "flowpilot-30001"
    assert record.usage == {"inputTokens": 10, "outputTokens": 4}
    assert record.is_terminal is True


async def test_the_resume_request_id_is_unique_per_invocation():
    store = InMemoryExecutionRecordStore()
    await store.create(
        invocation_id=30001, thread_id="flowpilot-30001", agent_type="REPOSITORY_ANALYST"
    )
    first, created = await store.create_resume(
        invocation_id=30001,
        resume_request_id="resume-001",
        thread_id="flowpilot-30001",
        status="COMPLETED",
        payload={"messageType": "AgentCompleted", "payload": {"invocationId": 30001}},
    )
    assert created is True

    second, created_again = await store.create_resume(
        invocation_id=30001,
        resume_request_id="resume-001",
        thread_id="flowpilot-30001",
        status="COMPLETED",
        payload={"messageType": "AgentCompleted", "payload": {"invocationId": 30001}},
    )
    assert created_again is False
    assert second.payload == first.payload

    # a different resume request of the same invocation is a new record
    third, created_third = await store.create_resume(
        invocation_id=30001,
        resume_request_id="resume-002",
        thread_id="flowpilot-30001",
        status="COMPLETED",
        payload={},
    )
    assert created_third is True
    assert third.resume_request_id == "resume-002"


async def test_a_claimed_resume_can_be_completed():
    store = InMemoryExecutionRecordStore()
    await store.create(
        invocation_id=30001, thread_id="flowpilot-30001", agent_type="REPOSITORY_ANALYST"
    )
    record, created = await store.create_resume(
        invocation_id=30001,
        resume_request_id="resume-001",
        thread_id="flowpilot-30001",
        status="PROCESSING",
        payload={},
    )
    assert created is True
    assert record.status == "PROCESSING"

    updated = await store.update_resume(
        30001,
        "resume-001",
        status="COMPLETED",
        payload={"messageType": "AgentCompleted", "payload": {"invocationId": 30001}},
        checkpoint_id="checkpoint-2",
    )
    assert updated is not None
    assert updated.status == "COMPLETED"
    assert updated.checkpoint_id == "checkpoint-2"
    assert updated.payload["payload"]["invocationId"] == 30001


async def test_an_unknown_invocation_has_no_record():
    store = InMemoryExecutionRecordStore()
    assert await store.get(999) is None
    assert await store.get_resume(999, "resume-001") is None


async def test_the_records_can_be_listed():
    store = InMemoryExecutionRecordStore()
    for invocation_id in (30001, 30002):
        await store.create(
            invocation_id=invocation_id,
            thread_id=f"flowpilot-{invocation_id}",
            agent_type="REPOSITORY_ANALYST",
        )
    await store.update(30001, status=STATUS_FAILED, error={"errorCode": "X"})

    records = await store.list_records()
    assert {record.invocation_id for record in records} == {30001, 30002}
