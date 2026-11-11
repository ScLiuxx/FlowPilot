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
from dataclasses import dataclass, field
from typing import Any, Callable

from langgraph.types import Command

from app.config.logging import get_logger, log_context
from app.config.settings import Settings, get_settings
from app.messaging.events import (
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
    dump_payload,
)
from app.persistence.checkpoint import expected_thread_id, latest_checkpoint_id
from app.persistence.repository import (
    RESUME_PROCESSING,
    STATUS_CANCELLED,
    STATUS_FAILED,
    STATUS_INTERRUPTED,
    STATUS_RUNNING,
    STATUS_SUCCEEDED,
    ExecutionRecordStore,
)
from app.runtime.context import AgentExecutionContext, ExecutionLimits, IdempotencyJournal, default_journal
from app.runtime.errors import (
    AgentExecutionError,
    InternalError,
    InvocationTimeoutError,
    ValidationError,
)
from app.runtime.registry import AgentGraphRegistry, registry as default_registry
from app.runtime.state import AgentState

logger = get_logger(__name__)

#: Status of an execution outcome.
OUTCOME_COMPLETED = "COMPLETED"
OUTCOME_FAILED = "FAILED"
OUTCOME_INTERRUPTED = "INTERRUPTED"
OUTCOME_DUPLICATE = "DUPLICATE"
OUTCOME_CANCELLED = "CANCELLED"


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
            try:
                self._validate_thread(payload.threadId, payload.invocationId)
                return await self._execute(envelope, payload)
            except AgentExecutionError as exc:
                return await self._fail(payload, exc, envelope)
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

    async def cancel(
        self, envelope: MessageEnvelope, payload: AgentCancelPayload
    ) -> ExecutionOutcome:
        """Handle an ``AgentCancel`` message (cooperative cancellation)."""
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
            await self._store.update(payload.invocationId, status=STATUS_CANCELLED)
            logger.info("agent.cancel applied", extra={"reason": payload.cancelReason})
            return ExecutionOutcome(
                status=OUTCOME_CANCELLED,
                message_type=MessageType.AGENT_FAILED,
                payload=AgentFailedPayload(
                    invocationId=payload.invocationId,
                    threadId=payload.threadId,
                    errorCode="AG-CANCELED-001",
                    errorCategory=ErrorCategory.BUSINESS,
                    errorMessage=payload.cancelReason or "agent invocation canceled",
                    retryable=False,
                ).model_dump(mode="json", exclude_none=True),
            )

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
                duplicate = self._duplicate_outcome(record, envelope)
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

        # The resume request is claimed *before* the graph is continued, so two
        # redeliveries of the same AgentResume can never resume the thread
        # twice (the unique constraint of the ledger makes the claim atomic).
        resume_record, claimed = await self._store.create_resume(
            invocation_id=invocation_id,
            resume_request_id=payload.resumeRequestId,
            thread_id=payload.threadId,
            status=RESUME_PROCESSING,
            payload={},
        )
        if not claimed:
            logger.info(
                "agent.resume ignored (resume request already processed)",
                extra={"resumeRequestId": payload.resumeRequestId, "duplicate": True},
            )
            if resume_record.status == RESUME_PROCESSING:
                # The first delivery is still running: do not publish anything.
                return ExecutionOutcome(
                    status=RESUME_PROCESSING,
                    message_type=MessageType.AGENT_FAILED,
                    payload={},
                    duplicate=True,
                    publish=False,
                )
            return ExecutionOutcome(
                status=resume_record.status,
                message_type=MessageType(resume_record.payload.get("messageType"))
                if resume_record.payload.get("messageType")
                else MessageType.AGENT_COMPLETED,
                payload=resume_record.payload.get("payload") or {},
                duplicate=True,
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
                duplicate = self._duplicate_outcome(record, envelope)
                if duplicate is not None:
                    await self._store.update_resume(
                        invocation_id,
                        payload.resumeRequestId,
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
            outcome = await self._finalize(envelope, payload, context, state)
        except BaseException as exc:
            # The resume request failed: release the claim with the failure so
            # that a redelivery returns the recorded error instead of resuming
            # the thread a second time.
            await self._store.update_resume(
                invocation_id,
                payload.resumeRequestId,
                status=STATUS_FAILED,
                payload={},
            )
            raise exc

        await self._store.update_resume(
            invocation_id,
            payload.resumeRequestId,
            status=outcome.status,
            payload={
                "messageType": outcome.message_type.value,
                "payload": outcome.payload,
            },
            checkpoint_id=outcome.payload.get("checkpointId"),
        )
        await self._store.update(
            invocation_id, resume_request_id=payload.resumeRequestId
        )
        return outcome

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
            limits=limits,
            input=dict(getattr(payload, "input", {}) or {}),
            llm=self._build_llm(),
            github=self._build_github(),
            journal=self._journal,
            workflow_execution_id=envelope.workflowExecutionId,
            node_execution_id=envelope.nodeExecutionId,
            attempt_no=envelope.attemptNo,
            correlation_id=envelope.correlationId,
        )

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
        try:
            result = await asyncio.wait_for(
                graph.ainvoke(graph_input, config, context=context),
                timeout=context.limits.timeout_seconds,
            )
        except asyncio.TimeoutError as exc:
            raise InvocationTimeoutError(
                f"agent invocation exceeded the wall clock budget of "
                f"{context.limits.timeout_seconds}s",
                diagnostics={
                    "timeoutSeconds": context.limits.timeout_seconds,
                    "invocationId": context.invocation_id,
                },
            ) from exc
        return result  # type: ignore[return-value]

    async def _finalize(
        self,
        envelope: MessageEnvelope,
        payload: Any,
        context: AgentExecutionContext,
        state: AgentState,
    ) -> ExecutionOutcome:
        invocation_id = payload.invocationId
        thread_id = payload.threadId
        checkpoint_id = await latest_checkpoint_id(self._checkpointer, thread_id)
        interrupts = state.get("__interrupt__") if isinstance(state, dict) else None

        if interrupts:
            return await self._interrupt(invocation_id, thread_id, checkpoint_id, interrupts)

        usage = {
            "inputTokens": int(state.get("inputTokens", 0) or 0),
            "outputTokens": int(state.get("outputTokens", 0) or 0),
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
        await self._store.update(
            invocation_id,
            status=STATUS_SUCCEEDED,
            checkpoint_id=checkpoint_id,
            result=dump_payload(completed),
            usage=usage,
        )
        logger.info(
            "agent completed",
            extra={"checkpointId": checkpoint_id, **usage},
        )
        return ExecutionOutcome(
            status=OUTCOME_COMPLETED,
            message_type=MessageType.AGENT_COMPLETED,
            payload=dump_payload(completed),
        )

    async def _interrupt(
        self,
        invocation_id: int,
        thread_id: str,
        checkpoint_id: str | None,
        interrupts: Any,
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
        await self._store.update(
            invocation_id,
            status=STATUS_INTERRUPTED,
            checkpoint_id=checkpoint_id,
        )
        logger.info("agent interrupted, waiting for human approval", extra={"checkpointId": checkpoint_id})
        return ExecutionOutcome(
            status=OUTCOME_INTERRUPTED,
            message_type=MessageType.AGENT_INTERRUPTED,
            payload=dump_payload(interrupted),
        )

    async def _fail(
        self,
        payload: Any,
        error: AgentExecutionError,
        envelope: MessageEnvelope,
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
        try:
            await self._store.update(
                invocation_id,
                status=STATUS_FAILED,
                error=dump_payload(failed),
            )
        except Exception:  # pragma: no cover - the store may be unavailable
            logger.exception("failed to persist the failed agent state")
        logger.warning(
            "agent failed",
            extra={
                "errorCode": error_payload.errorCode,
                "errorCategory": error_payload.errorCategory.value,
                "retryable": error_payload.retryable,
            },
        )
        return ExecutionOutcome(
            status=OUTCOME_FAILED,
            message_type=MessageType.AGENT_FAILED,
            payload=dump_payload(failed),
            error=error_payload,
        )

    def _duplicate_outcome(self, record: Any, envelope: MessageEnvelope) -> ExecutionOutcome | None:
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
            return ExecutionOutcome(
                status=record.status,
                message_type=MessageType.AGENT_COMPLETED,
                payload=record.result,
                duplicate=True,
            )
        if record.status == STATUS_INTERRUPTED:
            return ExecutionOutcome(
                status=record.status,
                message_type=MessageType.AGENT_INTERRUPTED,
                payload=AgentInterruptedPayload(
                    invocationId=record.invocation_id,
                    threadId=record.thread_id,
                    checkpointId=record.checkpoint_id,
                    interruptType="HUMAN_APPROVAL",
                ).model_dump(mode="json", exclude_none=True),
                duplicate=True,
            )
        if record.status in (STATUS_FAILED, STATUS_CANCELLED) and record.error:
            return ExecutionOutcome(
                status=record.status,
                message_type=MessageType.AGENT_FAILED,
                payload=record.error,
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
        inputTokens=0,
        outputTokens=0,
    )
