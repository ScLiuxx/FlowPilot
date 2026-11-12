"""Error taxonomy of the agent runtime.

The categories map 1:1 onto ``errorCategory`` of
``flowpilot-protocol/common/error.schema.json``:

* ``TRANSIENT``   — temporary infrastructure problem, the invocation may be
  retried (model timeout, upstream 5xx, wall clock budget exceeded).
* ``RATE_LIMIT``  — the upstream API throttled us (GitHub 403/429).
* ``VALIDATION``  — the message or a tool argument is invalid, retrying with
  the same input cannot help.
* ``PERMISSION``  — credentials are missing or insufficient.
* ``BUSINESS``    — a business rule or a configured budget was violated
  (max iterations / tool calls / tokens exhausted).
* ``INTERNAL``    — an unexpected defect inside this service.
"""

from __future__ import annotations

from typing import Any

from app.messaging.events import ErrorCategory, ErrorPayload


class AgentExecutionError(Exception):
    """Base class of every error that is reported to the Java backend."""

    default_code = "AG-EXECUTION-001"
    default_category = ErrorCategory.INTERNAL
    default_retryable = False

    def __init__(
        self,
        message: str,
        *,
        error_code: str | None = None,
        category: ErrorCategory | None = None,
        retryable: bool | None = None,
        diagnostics: dict[str, Any] | None = None,
    ) -> None:
        super().__init__(message)
        self.message = message
        self.error_code = error_code or self.default_code
        self.category = category or self.default_category
        self.retryable = self.default_retryable if retryable is None else retryable
        self.diagnostics = diagnostics

    def to_error_payload(self) -> ErrorPayload:
        return ErrorPayload(
            errorCode=self.error_code,
            errorCategory=self.category,
            errorMessage=self.message,
            retryable=self.retryable,
            diagnostics=self.diagnostics,
        )


class TransientError(AgentExecutionError):
    default_code = "AG-TRANSIENT-001"
    default_category = ErrorCategory.TRANSIENT
    default_retryable = True


class InternalError(AgentExecutionError):
    default_code = "AG-INTERNAL-001"
    default_category = ErrorCategory.INTERNAL
    default_retryable = False


class RateLimitError(AgentExecutionError):
    default_code = "AG-RATE-LIMIT-001"
    default_category = ErrorCategory.RATE_LIMIT
    default_retryable = True


class ValidationError(AgentExecutionError):
    default_code = "AG-VALIDATION-001"
    default_category = ErrorCategory.VALIDATION
    default_retryable = False


class PermissionError_(AgentExecutionError):
    default_code = "AG-PERMISSION-001"
    default_category = ErrorCategory.PERMISSION
    default_retryable = False


class BusinessError(AgentExecutionError):
    default_code = "AG-BUSINESS-001"
    default_category = ErrorCategory.BUSINESS
    default_retryable = False


#: Raised when one of the configured budgets is exhausted.
class BudgetExceededError(BusinessError):
    default_code = "AG-BUDGET-001"
    default_category = ErrorCategory.BUSINESS
    default_retryable = False


#: Raised when the wall clock budget of the invocation is exhausted.
class InvocationTimeoutError(TransientError):
    default_code = "AG-TIMEOUT-001"
    default_category = ErrorCategory.TRANSIENT
    default_retryable = True


#: Raised when the invocation was cancelled while it was running.
class AgentCancelledError(BusinessError):
    default_code = "AG-CANCELLED-001"
    default_category = ErrorCategory.BUSINESS
    default_retryable = False


#: Raised when a resume was taken over too often: the workers keep dying.
#:
#: The resume is given up. The ledger of the request and the invocation reach
#: the same terminal state, so the two state machines never contradict each
#: other. The checkpoint of the thread is kept, so the run can still be
#: examined.
class ResumeTakeoverLimitError(TransientError):
    default_code = "AG-RESUME-TOKEOVER-LIMIT-001"
    default_category = ErrorCategory.TRANSIENT
    default_retryable = True
