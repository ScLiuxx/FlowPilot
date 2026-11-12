"""Execution of an agent invocation.

The executor owns

* the idempotency rules of the protocol (``AgentExecute`` -> ``invocationId``,
  ``AgentResume`` -> ``invocationId`` + ``resumeRequestId``),
* the thread / checkpoint handling (``flowpilot-{invocationId}``, never
  regenerated),
* the budget enforcement (iterations, tool calls, tokens, wall clock) and
* the translation of every outcome into an ``AgentCompleted`` /
  ``AgentFailed`` / ``AgentInterrupted`` payload.
"""

from __future__ import annotations

import asyncio
import time
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from typing import Any, Callable

from langgraph.types import Command
from opentelemetry.trace import Status, StatusCode

from app.config.logging import get_logger, log_context
from app.config.settings import Settings, get_settings
from app.messaging.events import (
    ROUTING_BY_MESSAGE_TYPE,
    AgentCancelPayload,
    AgentCompletedPayload,
    AgentExecutePayload,
    AgentFailedPayload,
    AgentInterruptedPayload,
    AgentResumePayload,
    ErrorCategory,
    ErrorPayload,
    InterruptData,
    MessageEnvelope,
    MessageType,
    TokenUsage,
    build_outcome_envelope,
    dump_payload,
    envelope_to_dict,
)
from app.persistence.checkpoint import expected_thread_id, latest_checkpoint_id
from app.persistence.repository import (
    RESUME_CLAIMED,
    RESUME_IN_FLIGHT,
    RESUME_PROCESSING,
    STATUS_CANCELLED,
    STATUS_FAILED,
    STATUS_INTERRUPTED,
    STATUS_RUNNING,
    STATUS_SUCCEEDED,
    ExecutionRecordStore,
    GuardRejected,
    OutboxMessage,
    ResumeGuard,
)
from app.runtime.context import AgentExecutionContext, ExecutionLimits, IdempotencyJournal, default_journal
from app.runtime.errors import (
    AgentCancelledError,
    AgentExecutionError,
    InternalError,
    InvocationTimeoutError,
    ValidationError,
)
from app.runtime.registry import AgentGraphRegistry, registry as default_registry
from app.runtime.state import AgentState
from app.telemetry import get_tracer, parent_context

logger = get_logger(__name__)

#: Status of an execution outcome.
OUTCOME_COMPLETED = "COMPLETED"
OUTCOME_FAILED = "FAILED"
OUTCOME_INTERRUPTED = "INTERRUPTED"
OUTCOME_DUPLICATE = "DUPLICATE"
OUTCOME_CANCELLED = "CANCELLED"

#: The states an invocation may have while it is still being worked on. A
#: terminal update (the success of a run, a cancel) is only applied while the
#: invocation is in one of them — a cancelled invocation therefore never
#: receives a late success event.
LIVE_STATUSES = (STATUS_RUNNING, STATUS_INTERRUPTED)


@dataclass
class ExecutionOutcome:
    """What the consumer has to publish for one handled message."""

    status: str
    message_type: MessageType
    payload: dict[str, Any]
    duplicate: bool = False
    publish: bool = True
    error: ErrorPayload | None = None
    diagnostics: dict[str, Any] = field(default_factory=dict)
    #: True when the outcome was discarded because the invocation had already
    #: reached another terminal state (a cancel for example).
    superseded: bool = False


@dataclass
class _CancelState:
    """The cancellation state of one running invocation."""

    #: Set when a cancel was requested for the invocation.
    requested: bool = False
    reason: str | None = None
    #: The moment the store was read the last time.
    last_poll: float = 0.0


class AgentExecutor:
    """Runs one agent invocation (or resumes a waiting one)."""

    def __init__(
        self,
        *,
        store: ExecutionRecordStore,
        checkpointer: Any,
        registry: AgentGraphRegistry | None = None,
        settings: Settings | None = None,
        llm_factory: Callable[[], Any] | None = None,
        github_factory: Callable[[], Any] | None = None,
        journal: IdempotencyJournal | None = None,
    ) -> None:
        self._store = store
        self._checkpointer = checkpointer
        self._registry = registry or default_registry
        self._settings = settings or get_settings()
        self._llm_factory = llm_factory
        self._github_factory = github_factory
        self._journal = journal or default_journal()
        # The asyncio task of every running invocation. The AgentCancel command
        # uses it to stop the graph of a running invocation.
        self._running: dict[int, asyncio.Task[Any]] = {}
        # The cancellation state of every running invocation.
        self._cancel_states: dict[int, _CancelState] = {}

    # -- collaborators ----------------------------------------------------
    def _build_llm(self) -> Any:
        if self._llm_factory is not None:
            return self._llm_factory()
        from app.llm.provider import build_chat_model

        return build_chat_model(self._settings)

    def _build_github(self) -> Any:
        if self._github_factory is not None:
            return self._github_factory()
        from app.tools.github_tool import GitHubTool

        return GitHubTool(
            token=self._settings.github_token,
            timeout=self._settings.github_timeout_seconds,
            max_items=self._settings.github_max_items,
            max_content_chars=self._settings.github_max_content_chars,
        )

    # -- public API -------------------------------------------------------
    async def execute(
        self, envelope: MessageEnvelope, payload: AgentExecutePayload
    ) -> ExecutionOutcome:
        """Handle an ``AgentExecute`` message."""
        with log_context(
            trace_id=envelope.traceId,
            invocation_id=payload.invocationId,
            thread_id=payload.threadId,
            message_id=envelope.messageId,
        ):
            logger.info(
                "agent.execute received",
                extra={"agentType": payload.agentType, "attemptNo": envelope.attemptNo},
            )
            self._cancel_states.setdefault(payload.invocationId, _CancelState())
            try:
                self._validate_thread(payload.threadId, payload.invocationId)
                return await self._execute(envelope, payload)
            except AgentExecutionError as exc:
                return await self._fail(payload, exc, envelope)
            except asyncio.CancelledError:
                # The worker is shutting down: the invocation must not stay in
                # the running state forever, the Java backend retries it.
                await self._abandon(payload.invocationId)
                raise
            except Exception as exc:  # pragma: no cover - defensive
                logger.exception("unexpected failure during agent execution")
                return await self._fail(
                    payload,
                    InternalError(
                        f"unexpected agent runtime error: {type(exc).__name__}",
                        diagnostics={"error": str(exc)},
                    ),
                    envelope,
                )
            finally:
                self._cancel_states.pop(payload.invocationId, None)

    async def resume(
        self, envelope: MessageEnvelope, payload: AgentResumePayload
    ) -> ExecutionOutcome:
        """Handle an ``AgentResume`` message."""
        with log_context(
            trace_id=envelope.traceId,
            invocation_id=payload.invocationId,
            thread_id=payload.threadId,
            message_id=envelope.messageId,
        ):
            logger.info(
                "agent.resume received",
                extra={"resumeRequestId": payload.resumeRequestId},
            )
            self._cancel_states.setdefault(payload.invocationId, _CancelState())
            try:
                self._validate_thread(payload.threadId, payload.invocationId)
                return await self._resume(envelope, payload)
            except AgentExecutionError as exc:
                return await self._fail(
                    AgentExecutePayload(
                        agentType="",
                        threadId=payload.threadId,
                        invocationId=payload.invocationId,
                    ),
                    exc,
                    envelope,
                )
            except asyncio.CancelledError:
                await self._abandon(payload.invocationId)
                raise
            except Exception as exc:  # pragma: no cover - defensive
                logger.exception("unexpected failure during agent resume")
                return await self._fail(
                    AgentExecutePayload(
                        agentType="",
                        threadId=payload.threadId,
                        invocationId=payload.invocationId,
                    ),
                    InternalError(
                        f"unexpected agent runtime error: {type(exc).__name__}",
                        diagnostics={"error": str(exc)},
                    ),
                    envelope,
                )
            finally:
                self._cancel_states.pop(payload.invocationId, None)

    async def cancel(
        self, envelope: MessageEnvelope, payload: AgentCancelPayload
    ) -> ExecutionOutcome:
        """Handle an ``AgentCancel`` message (cooperative cancellation).

        The cancel is a *request*: the running graph is stopped where it can be
        stopped, the side effects it already caused are of course not undone.
        The terminal state is written first and conditionally — a success that
        is already on its way therefore wins the race, and a cancelled
        invocation never receives a late success event.
        """
        with log_context(
            trace_id=envelope.traceId,
            invocation_id=payload.invocationId,
            thread_id=payload.threadId,
            message_id=envelope.messageId,
        ):
            record = await self._store.get(payload.invocationId)
            if record is None:
                raise ValidationError(
                    f"unknown agent invocation {payload.invocationId}",
                    error_code="AG-INVOCATION-001",
                )
            failed = AgentFailedPayload(
                invocationId=payload.invocationId,
                threadId=payload.threadId,
                errorCode="AG-CANCELED-001",
                errorCategory=ErrorCategory.BUSINESS,
                errorMessage=payload.cancelReason or "agent invocation canceled",
                retryable=False,
            )
            outcome = await self._persist_outcome(
                envelope,
                invocation_id=payload.invocationId,
                status=STATUS_CANCELLED,
                outcome_status=OUTCOME_CANCELLED,
                message_type=MessageType.AGENT_FAILED,
                payload=dump_payload(failed),
                error=dump_payload(failed),
                guard_statuses=LIVE_STATUSES,
            )
            # The state is durable now — the running graph is stopped.
            state = self._cancel_states.setdefault(payload.invocationId, _CancelState())
            state.requested = True
            state.reason = payload.cancelReason
            task = self._running.get(payload.invocationId)
            if task is not None:
                task.cancel()
            logger.info(
                "agent.cancel applied",
                extra={
                    "reason": payload.cancelReason,
                    "superseded": outcome.superseded,
                    "stoppedRunningGraph": task is not None,
                },
            )
            return outcome

    # -- internals --------------------------------------------------------
    @staticmethod
    def _validate_thread(thread_id: str, invocation_id: int) -> None:
        expected = expected_thread_id(invocation_id)
        if thread_id != expected:
            raise ValidationError(
                "threadId must be 'flowpilot-{invocationId}'",
                error_code="AG-THREAD-ID-001",
                diagnostics={"threadId": thread_id, "expected": expected},
            )

    def _cancel_requested(self, invocation_id: int) -> bool:
        """Whether a cancel was requested for the invocation."""
        state = self._cancel_states.get(invocation_id)
        return bool(state and state.requested)

    async def _abandon(self, invocation_id: int) -> None:
        """Mark an invocation as failed when the worker stops in the middle of it.

        Without it the invocation would stay in the running state and the Java
        backend would wait for a result that never arrives.
        """
        try:
            await self._store.update(
                invocation_id,
                status=STATUS_FAILED,
                error={
                    "errorCode": "AG-WORKER-LOST-001",
                    "errorCategory": ErrorCategory.TRANSIENT.value,
                    "errorMessage": "the agent worker stopped while the invocation was running",
                    "retryable": True,
                },
            )
        except Exception:  # pragma: no cover - best effort during shutdown
            logger.exception("could not mark the abandoned invocation as failed")

    async def _execute(
        self, envelope: MessageEnvelope, payload: AgentExecutePayload
    ) -> ExecutionOutcome:
        invocation_id = payload.invocationId
        record, created = await self._store.create(
            invocation_id=invocation_id,
            thread_id=payload.threadId,
            agent_type=payload.agentType,
            status=STATUS_RUNNING,
            attempt_no=envelope.attemptNo,
            message_id=envelope.messageId,
        )
        if not created:
            is_retry = (
                record.status == STATUS_FAILED
                and envelope.attemptNo is not None
                and record.attempt_no is not None
                and envelope.attemptNo > record.attempt_no
            )
            if not is_retry:
                duplicate = await self._duplicate_outcome(record, envelope)
                if duplicate is not None:
                    logger.info(
                        "agent.execute ignored (already processed)",
                        extra={"status": record.status, "duplicate": True},
                    )
                    return duplicate
            else:
                # A retry of a failed attempt: the attempt number increased, so
                # the invocation is executed again on the very same thread.
                await self._store.update(
                    invocation_id,
                    status=STATUS_RUNNING,
                    attempt_no=envelope.attemptNo,
                    resume_request_id=None,
                )
                logger.info(
                    "agent.execute retried on the same thread",
                    extra={"attemptNo": envelope.attemptNo},
                )

        context = self._build_context(envelope, payload)
        state = await self._run_graph(context, payload.threadId, initial_state(payload))
        return await self._finalize(envelope, payload, context, state)

    async def _resume(
        self, envelope: MessageEnvelope, payload: AgentResumePayload
    ) -> ExecutionOutcome:
        invocation_id = payload.invocationId
        resume_request_id = payload.resumeRequestId

        # The resume request is claimed *before* the graph is continued: the
        # unique constraint of the ledger makes the claim atomic, and the lease
        # of the ledger says who is allowed to continue the thread.
        record, created = await self._store.create_resume(
            invocation_id=invocation_id,
            resume_request_id=resume_request_id,
            thread_id=payload.threadId,
            status=RESUME_CLAIMED,
            payload={},
            lease_expire_at=_utcnow()
            + timedelta(seconds=self._settings.resume_lease_seconds),
        )
        lease_token = record.lease_token
        takeover = False

        if not created:
            if record.status not in RESUME_IN_FLIGHT:
                # The resume was already completed: the recorded result is
                # published again so the backend learns about it.
                logger.info(
                    "agent.resume replayed (already completed)",
                    extra={"resumeRequestId": resume_request_id, "duplicate": True},
                )
                return await self._replay_resume(envelope, invocation_id, record)
            # The resume is in flight. It is either a redelivery of a message
            # that is being processed right now, or the remains of a worker
            # that died. The lease tells the two apart.
            lease = await self._take_over_resume(invocation_id, resume_request_id, record)
            if lease is None:
                logger.info(
                    "agent.resume ignored (another worker owns the resume)",
                    extra={"resumeRequestId": resume_request_id, "duplicate": True},
                )
                return ExecutionOutcome(
                    status=RESUME_PROCESSING,
                    message_type=MessageType.AGENT_FAILED,
                    payload={},
                    duplicate=True,
                    publish=False,
                )
            record = lease
            lease_token = lease.lease_token
            takeover = True
            takeover_count = lease.takeover_count

        lease = (resume_request_id, lease_token)
        renewer = asyncio.create_task(
            self._renew_resume_lease(invocation_id, resume_request_id, lease_token)
        )
        try:
            record = await self._store.get(invocation_id)
            if record is None:
                raise ValidationError(
                    f"unknown agent invocation {invocation_id}",
                    error_code="AG-INVOCATION-001",
                )
            if record.thread_id != payload.threadId:
                raise ValidationError(
                    "threadId does not match the recorded invocation thread",
                    error_code="AG-THREAD-ID-002",
                    diagnostics={
                        "threadId": payload.threadId,
                        "recordedThreadId": record.thread_id,
                    },
                )
            if record.status == STATUS_SUCCEEDED:
                duplicate = await self._duplicate_outcome(record, envelope)
                if duplicate is not None:
                    await self._store.complete_resume(
                        invocation_id,
                        resume_request_id,
                        lease_token=lease_token,
                        status=duplicate.status,
                        payload={
                            "messageType": duplicate.message_type.value,
                            "payload": duplicate.payload,
                        },
                        checkpoint_id=duplicate.payload.get("checkpointId"),
                    )
                    return duplicate
            if record.status not in (STATUS_INTERRUPTED, STATUS_RUNNING):
                raise ValidationError(
                    f"agent invocation {invocation_id} is not resumable "
                    f"(status={record.status})",
                    error_code="AG-STATE-001",
                    diagnostics={"status": record.status},
                )

            context = self._build_context(envelope, payload, record=record)
            state = await self._run_graph(
                context,
                payload.threadId,
                Command(resume=payload.resumeValue or {}),
            )
            outcome = await self._finalize(
                envelope, payload, context, state, resume_lease=lease
            )
        except asyncio.CancelledError:
            # The worker is stopping: the lease is left to expire, so that
            # another worker can take the resume over.
            raise
        except AgentExecutionError as exc:
            # The failure event and the ledger are written in one transaction,
            # therefore a redelivery of the resume returns the recorded error
            # instead of resuming the thread a second time.
            return await self._fail(
                AgentExecutePayload(
                    agentType="",
                    threadId=payload.threadId,
                    invocationId=invocation_id,
                ),
                exc,
                envelope,
                resume_lease=lease,
            )
        finally:
            renewer.cancel()

        await self._store.update(
            invocation_id, resume_request_id=resume_request_id
        )
        if takeover:
            logger.info(
                "the resume was taken over by another worker",
                extra={
                    "resumeRequestId": resume_request_id,
                    "leaseToken": lease_token,
                    "takeoverCount": takeover_count,
                },
            )
        return outcome

    async def _replay_resume(
        self, envelope: MessageEnvelope, invocation_id: int, record: Any
    ) -> ExecutionOutcome:
        """Publish the recorded result of an already completed resume."""
        invocation = await self._store.get(invocation_id)
        return await self._persist_outcome(
            envelope,
            invocation_id=invocation_id,
            status=invocation.status if invocation is not None else STATUS_SUCCEEDED,
            outcome_status=record.status,
            message_type=MessageType(record.payload["messageType"])
            if record.payload.get("messageType")
            else MessageType.AGENT_COMPLETED,
            payload=record.payload.get("payload") or {},
            duplicate=True,
        )

    async def _take_over_resume(
        self, invocation_id: int, resume_request_id: str, record: Any
    ) -> Any:
        """Wait for the lease of a running resume to expire and take it over.

        The takeover is the reason a resume cannot get stuck forever: a worker
        that dies while it continues the graph leaves the ledger in the
        ``CLAIMED`` state with an expired lease. The next delivery of the
        resume command — the broker redelivers it, because the dead worker
        never acknowledged it — finds the expired lease and continues the very
        same thread and checkpoint.

        The lease is the fence: only one worker can take it over, and the
        worker that lost it must not write the result anymore.
        """
        # The wait covers the remaining lifetime of the lease, so the takeover
        # is always possible. The additional grace period covers the moment
        # between the expiry and the next poll.
        deadline = time.monotonic() + (
            self._settings.resume_lease_seconds + self._settings.resume_lease_wait_seconds
        )
        while True:
            current = await self._store.get_resume(invocation_id, resume_request_id)
            if current is None or current.status not in RESUME_IN_FLIGHT:
                # The owner finished the resume in the meantime.
                return None
            if current.takeover_count >= self._settings.resume_max_takeovers:
                logger.error(
                    "the resume was taken over too often, giving up",
                    extra={
                        "resumeRequestId": resume_request_id,
                        "takeoverCount": current.takeover_count,
                    },
                )
                raise ValidationError(
                    "the resume was taken over too often, the worker keeps dying",
                    error_code="AG-RESUME-TOKEOVER-LIMIT-001",
                    category=ErrorCategory.TRANSIENT,
                    retryable=True,
                    diagnostics={"takeoverCount": current.takeover_count},
                )
            now = _utcnow()
            if current.lease_expire_at is None or current.lease_expire_at <= now:
                claimed = await self._store.claim_resume_lease(
                    invocation_id,
                    resume_request_id,
                    lease_expire_at=now
                    + timedelta(seconds=self._settings.resume_lease_seconds),
                    expected_lease_token=current.lease_token,
                )
                if claimed is not None:
                    logger.warning(
                        "the resume lease expired, the resume is taken over",
                        extra={
                            "resumeRequestId": resume_request_id,
                            "previousLeaseToken": current.lease_token,
                            "leaseToken": claimed.lease_token,
                            "takeoverCount": claimed.takeover_count,
                        },
                    )
                    return claimed
                # Somebody else took the lease over first.
                continue
            if time.monotonic() >= deadline:
                logger.warning(
                    "the resume is still owned by another worker",
                    extra={
                        "resumeRequestId": resume_request_id,
                        "leaseToken": current.lease_token,
                    },
                )
                return None
            await asyncio.sleep(self._settings.resume_lease_poll_seconds)

    async def _renew_resume_lease(
        self, invocation_id: int, resume_request_id: str, lease_token: int
    ) -> None:
        """Keep the lease of a running resume alive.

        The lease is renewed while the graph is continued. When the renewal
        fails the lease was taken over by another worker — this worker must
        stop, otherwise two workers would continue the same thread.
        """
        interval = max(1.0, self._settings.resume_lease_seconds / 3)
        while True:
            await asyncio.sleep(interval)
            try:
                renewed = await self._store.renew_resume_lease(
                    invocation_id,
                    resume_request_id,
                    lease_token=lease_token,
                    lease_expire_at=_utcnow()
                    + timedelta(seconds=self._settings.resume_lease_seconds),
                )
            except asyncio.CancelledError:
                raise
            except Exception:  # pragma: no cover - defensive
                logger.exception("the resume lease could not be renewed")
                continue
            if not renewed:
                logger.error(
                    "the resume lease was lost, the worker stops",
                    extra={
                        "resumeRequestId": resume_request_id,
                        "leaseToken": lease_token,
                    },
                )
                task = self._running.get(invocation_id)
                if task is not None:
                    task.cancel()
                return

    def _build_context(
        self,
        envelope: MessageEnvelope,
        payload: Any,
        record: Any = None,
    ) -> AgentExecutionContext:
        config = getattr(payload, "config", None)
        limits = ExecutionLimits.from_config(config) if config is not None else ExecutionLimits(
            max_iterations=self._settings.default_max_iterations,
            max_tool_calls=self._settings.default_max_tool_calls,
            max_tokens=self._settings.default_max_tokens,
            timeout_seconds=self._settings.default_timeout_seconds,
        )
        agent_type = getattr(payload, "agentType", "") or (record.agent_type if record else "")
        return AgentExecutionContext(
            invocation_id=payload.invocationId,
            thread_id=payload.threadId,
            agent_type=agent_type,
            trace_id=envelope.traceId,
            message_id=envelope.messageId,
            parent_span_id=envelope.spanId,
            limits=limits,
            input=dict(getattr(payload, "input", {}) or {}),
            llm=self._build_llm(),
            github=self._build_github(),
            journal=self._journal,
            workflow_execution_id=envelope.workflowExecutionId,
            node_execution_id=envelope.nodeExecutionId,
            attempt_no=envelope.attemptNo,
            correlation_id=envelope.correlationId,
            cancel_probe=lambda: self._cancel_probe(payload.invocationId),
        )

    async def _cancel_probe(self, invocation_id: int) -> bool:
        """Whether the invocation was cancelled.

        The in-process flag answers immediately. The store is only read every
        few seconds — a cancel that was handled by another replica is noticed
        with that delay, which is good enough: the node is stopped at the next
        check anyway.
        """
        if self._cancel_requested(invocation_id):
            return True
        state = self._cancel_states.get(invocation_id)
        now = time.monotonic()
        if state is not None and now - state.last_poll < self._settings.cancel_poll_seconds:
            return False
        if state is not None:
            state.last_poll = now
        try:
            record = await self._store.get(invocation_id)
        except Exception:  # pragma: no cover - the store is temporarily down
            logger.exception("the cancel probe could not read the execution record")
            return False
        if record is not None and record.status == STATUS_CANCELLED:
            if state is not None:
                state.requested = True
                state.reason = "the invocation was cancelled"
            return True
        return False

    async def _run_graph(
        self,
        context: AgentExecutionContext,
        thread_id: str,
        graph_input: Any,
    ) -> AgentState:
        graph = self._registry.build(context.agent_type, context).compile(
            checkpointer=self._checkpointer
        )
        config = {"configurable": {"thread_id": thread_id}}
        tracer = get_tracer()
        # The span joins the trace the Java backend opened: the trace id of the
        # envelope *is* the OpenTelemetry trace id.
        span_context = parent_context(
            context.trace_id, context.message_id, context.parent_span_id
        )
        with tracer.start_as_current_span(
            "agent.graph",
            context=span_context,
            attributes={
                "flowpilot.invocation_id": context.invocation_id,
                "flowpilot.agent_type": context.agent_type,
                "flowpilot.thread_id": context.thread_id,
            },
        ) as span:
            # The graph runs in its own task: the AgentCancel command can then
            # stop it, even while it waits for the model or for a tool.
            task = asyncio.create_task(
                graph.ainvoke(graph_input, config, context=context)
            )
            self._running[context.invocation_id] = task
            try:
                result = await asyncio.wait_for(
                    task,
                    timeout=context.limits.timeout_seconds,
                )
            except asyncio.TimeoutError as exc:
                span.set_status(Status(StatusCode.ERROR, "timeout"))
                raise InvocationTimeoutError(
                    f"agent invocation exceeded the wall clock budget of "
                    f"{context.limits.timeout_seconds}s",
                    diagnostics={
                        "timeoutSeconds": context.limits.timeout_seconds,
                        "invocationId": context.invocation_id,
                    },
                ) from exc
            except asyncio.CancelledError:
                span.set_status(Status(StatusCode.ERROR, "cancelled"))
                if self._cancel_requested(context.invocation_id):
                    # The invocation was cancelled on purpose: this is a
                    # business outcome, not a defect of the worker.
                    raise AgentCancelledError(
                        "the agent invocation was cancelled while it was running"
                    ) from None
                raise
            except Exception as exc:
                span.record_exception(exc)
                span.set_status(Status(StatusCode.ERROR, type(exc).__name__))
                raise
            finally:
                self._running.pop(context.invocation_id, None)
        return result  # type: ignore[return-value]

    async def _finalize(
        self,
        envelope: MessageEnvelope,
        payload: Any,
        context: AgentExecutionContext,
        state: AgentState,
        resume_lease: tuple[str, int] | None = None,
    ) -> ExecutionOutcome:
        invocation_id = payload.invocationId
        thread_id = payload.threadId
        checkpoint_id = await latest_checkpoint_id(self._checkpointer, thread_id)
        interrupts = state.get("__interrupt__") if isinstance(state, dict) else None

        if interrupts:
            return await self._interrupt(
                envelope, invocation_id, thread_id, checkpoint_id, interrupts, resume_lease
            )

        usage = {
            "inputTokens": int(state.get("inputTokens", 0) or 0),
            "outputTokens": int(state.get("outputTokens", 0) or 0),
            "llmCalls": _counter(state, "llmCalls", context.counters.llm_calls),
            "toolCalls": _counter(state, "toolCalls", context.counters.tool_calls),
            "toolFailures": context.counters.tool_failures,
        }
        result_payload = dict(state.get("analysis") or {})
        result_payload["repository"] = state.get("repository")
        report = state.get("report")
        if report:
            result_payload["report"] = report
        if state.get("review"):
            result_payload["review"] = state["review"]

        completed = AgentCompletedPayload(
            invocationId=invocation_id,
            threadId=thread_id,
            checkpointId=checkpoint_id,
            result=result_payload,
            usage=TokenUsage(**usage),
        )
        logger.info(
            "agent completed",
            extra={"checkpointId": checkpoint_id, **usage},
        )
        return await self._persist_outcome(
            envelope,
            invocation_id=invocation_id,
            status=STATUS_SUCCEEDED,
            outcome_status=OUTCOME_COMPLETED,
            message_type=MessageType.AGENT_COMPLETED,
            payload=dump_payload(completed),
            checkpoint_id=checkpoint_id,
            result=dump_payload(completed),
            usage=usage,
            guard_statuses=LIVE_STATUSES,
            resume_guard=self._resume_guard(
                resume_lease,
                status=OUTCOME_COMPLETED,
                message_type=MessageType.AGENT_COMPLETED,
                payload=dump_payload(completed),
                checkpoint_id=checkpoint_id,
            ),
        )

    async def _interrupt(
        self,
        envelope: MessageEnvelope,
        invocation_id: int,
        thread_id: str,
        checkpoint_id: str | None,
        interrupts: Any,
        resume_lease: tuple[str, int] | None = None,
    ) -> ExecutionOutcome:
        first = interrupts[0] if isinstance(interrupts, (list, tuple)) else interrupts
        value = getattr(first, "value", None)
        if not isinstance(value, dict):
            value = {"payload": value}
        interrupted = AgentInterruptedPayload(
            invocationId=invocation_id,
            threadId=thread_id,
            checkpointId=checkpoint_id,
            interruptType="HUMAN_APPROVAL",
            interruptData=InterruptData(
                title=value.get("title"),
                description=value.get("description"),
                requestedAction=value.get("requestedAction"),
                payload=value.get("payload") or {},
            ),
        )
        logger.info("agent interrupted, waiting for human approval", extra={"checkpointId": checkpoint_id})
        return await self._persist_outcome(
            envelope,
            invocation_id=invocation_id,
            status=STATUS_INTERRUPTED,
            outcome_status=OUTCOME_INTERRUPTED,
            message_type=MessageType.AGENT_INTERRUPTED,
            payload=dump_payload(interrupted),
            checkpoint_id=checkpoint_id,
            guard_statuses=LIVE_STATUSES,
            resume_guard=self._resume_guard(
                resume_lease,
                status=OUTCOME_INTERRUPTED,
                message_type=MessageType.AGENT_INTERRUPTED,
                payload=dump_payload(interrupted),
                checkpoint_id=checkpoint_id,
            ),
        )

    @staticmethod
    def _resume_guard(
        lease: tuple[str, int] | None,
        *,
        status: str,
        message_type: MessageType,
        payload: dict[str, Any],
        checkpoint_id: str | None,
    ) -> ResumeGuard | None:
        """Build the fence of a resume request, if the run belongs to one."""
        if lease is None:
            return None
        resume_request_id, lease_token = lease
        return ResumeGuard(
            resume_request_id=resume_request_id,
            lease_token=lease_token,
            status=status,
            payload={
                "messageType": message_type.value,
                "payload": payload,
            },
            checkpoint_id=checkpoint_id,
        )

    async def _fail(
        self,
        payload: Any,
        error: AgentExecutionError,
        envelope: MessageEnvelope,
        resume_lease: tuple[str, int] | None = None,
    ) -> ExecutionOutcome:
        invocation_id = getattr(payload, "invocationId", None) or envelope.invocationId or 0
        thread_id = getattr(payload, "threadId", "") or expected_thread_id(invocation_id)
        error_payload = error.to_error_payload()
        failed = AgentFailedPayload(
            invocationId=invocation_id,
            threadId=thread_id,
            checkpointId=None,
            errorCode=error_payload.errorCode,
            errorCategory=error_payload.errorCategory,
            errorMessage=error_payload.errorMessage,
            retryable=error_payload.retryable,
            diagnostics=error_payload.diagnostics,
        )
        logger.warning(
            "agent failed",
            extra={
                "errorCode": error_payload.errorCode,
                "errorCategory": error_payload.errorCategory.value,
                "retryable": error_payload.retryable,
            },
        )
        try:
            return await self._persist_outcome(
                envelope,
                invocation_id=invocation_id,
                status=STATUS_FAILED,
                outcome_status=OUTCOME_FAILED,
                message_type=MessageType.AGENT_FAILED,
                payload=dump_payload(failed),
                error=dump_payload(failed),
                error_payload=error_payload,
                guard_statuses=LIVE_STATUSES,
                resume_guard=self._resume_guard(
                    resume_lease,
                    status=STATUS_FAILED,
                    message_type=MessageType.AGENT_FAILED,
                    payload=dump_payload(failed),
                    checkpoint_id=None,
                ),
            )
        except Exception:
            # The failure could not be persisted: the command has to be retried,
            # otherwise the backend would never learn about the failure.
            logger.exception("failed to persist the failed agent state")
            raise

    async def _persist_outcome(
        self,
        envelope: MessageEnvelope,
        *,
        invocation_id: int,
        status: str,
        outcome_status: str,
        message_type: MessageType,
        payload: dict[str, Any],
        checkpoint_id: str | None = None,
        result: dict[str, Any] | None = None,
        error: dict[str, Any] | None = None,
        usage: dict[str, Any] | None = None,
        error_payload: ErrorPayload | None = None,
        duplicate: bool = False,
        guard_statuses: tuple[str, ...] | None = None,
        resume_guard: ResumeGuard | None = None,
    ) -> ExecutionOutcome:
        """Persist the outcome and enqueue its event in one transaction.

        The event is not published directly: it is written to the transactional
        outbox together with the execution record, therefore the result can
        never be lost when the service dies right after the execution.

        ``guard_statuses`` makes the write conditional: when the invocation
        already reached another terminal state (a cancel for example), the
        outcome is discarded and no event is enqueued. The backend therefore
        never sees a success after a cancel.

        ``resume_guard`` closes the resume ledger in the same transaction and
        only while the lease of the resume request is still owned by this
        worker — a worker whose lease was taken over publishes nothing.
        """
        event = build_outcome_envelope(envelope, message_type, payload)
        outbox = OutboxMessage(
            message_id=event.messageId,
            message_type=message_type.value,
            routing_key=ROUTING_BY_MESSAGE_TYPE.get(message_type, message_type.value),
            payload=envelope_to_dict(event),
        )
        try:
            await self._store.update_with_outcome(
                invocation_id,
                status=status,
                checkpoint_id=checkpoint_id,
                result=result,
                error=error,
                usage=usage,
                outbox=outbox,
                guard_statuses=guard_statuses,
                resume_guard=resume_guard,
            )
        except GuardRejected as exc:
            logger.info(
                "the outcome was discarded, the invocation already reached "
                "another terminal state",
                extra={
                    "invocationId": invocation_id,
                    "status": status,
                    "reason": str(exc),
                },
            )
            return ExecutionOutcome(
                status=outcome_status,
                message_type=message_type,
                payload=payload,
                duplicate=True,
                publish=False,
                error=error_payload,
                superseded=True,
            )
        return ExecutionOutcome(
            status=outcome_status,
            message_type=message_type,
            payload=payload,
            duplicate=duplicate,
            publish=False,
            error=error_payload,
        )

    async def _duplicate_outcome(
        self, record: Any, envelope: MessageEnvelope
    ) -> ExecutionOutcome | None:
        """Build the outcome of a redelivered message (no new run)."""
        if record.status == STATUS_RUNNING:
            # A previous delivery is still running: never start a second run
            # and do not publish anything — the running attempt reports itself.
            return ExecutionOutcome(
                status=record.status,
                message_type=MessageType.AGENT_FAILED,
                payload=AgentFailedPayload(
                    invocationId=record.invocation_id,
                    threadId=record.thread_id,
                    errorCode="AG-IN-PROGRESS-001",
                    errorCategory=ErrorCategory.TRANSIENT,
                    errorMessage="the agent invocation is already running",
                    retryable=True,
                ).model_dump(mode="json", exclude_none=True),
                duplicate=True,
                publish=False,
            )
        if record.status == STATUS_SUCCEEDED and record.result:
            return await self._persist_outcome(
                envelope,
                invocation_id=record.invocation_id,
                status=record.status,
                outcome_status=record.status,
                message_type=MessageType.AGENT_COMPLETED,
                payload=record.result,
                checkpoint_id=record.checkpoint_id,
                result=record.result,
                usage=record.usage,
                duplicate=True,
            )
        if record.status == STATUS_INTERRUPTED:
            return await self._persist_outcome(
                envelope,
                invocation_id=record.invocation_id,
                status=record.status,
                outcome_status=record.status,
                message_type=MessageType.AGENT_INTERRUPTED,
                payload=AgentInterruptedPayload(
                    invocationId=record.invocation_id,
                    threadId=record.thread_id,
                    checkpointId=record.checkpoint_id,
                    interruptType="HUMAN_APPROVAL",
                ).model_dump(mode="json", exclude_none=True),
                checkpoint_id=record.checkpoint_id,
                duplicate=True,
            )
        if record.status in (STATUS_FAILED, STATUS_CANCELLED) and record.error:
            return await self._persist_outcome(
                envelope,
                invocation_id=record.invocation_id,
                status=record.status,
                outcome_status=record.status,
                message_type=MessageType.AGENT_FAILED,
                payload=record.error,
                error=record.error,
                duplicate=True,
            )
        return None


def initial_state(payload: AgentExecutePayload) -> AgentState:
    """Build the initial graph state of an ``AgentExecute``."""
    return AgentState(
        input=dict(payload.input or {}),
        agentType=payload.agentType,
        iterations=0,
        toolCalls=0,
        llmCalls=0,
        inputTokens=0,
        outputTokens=0,
    )


def _counter(state: AgentState, key: str, fallback: int) -> int:
    """A counter of the checkpointed state, falling back to the run counters.

    The state survives an interrupt / resume cycle, the run counters do not:
    the state is therefore preferred whenever it carries a value.
    """
    value = int(state.get(key, 0) or 0)
    return value or fallback


def _utcnow() -> datetime:
    """The current time in UTC (the leases are compared against it)."""
    return datetime.now(tz=timezone.utc)
