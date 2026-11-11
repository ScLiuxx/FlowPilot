package com.flowpilot.execution.model;

/**
 * Execution event types. These are persisted in {@code execution_event} and streamed to the
 * console over SSE.
 */
public enum EventType {

    WORKFLOW_CREATED,
    WORKFLOW_STARTED,
    WORKFLOW_SUCCEEDED,
    WORKFLOW_FAILED,
    WORKFLOW_CANCELED,
    WORKFLOW_RETRY_WAIT,
    WORKFLOW_WAITING_HUMAN,
    NODE_READY,
    NODE_STARTED,
    NODE_SUCCEEDED,
    NODE_FAILED,
    NODE_RETRY,
    NODE_SKIPPED,
    NODE_CANCELED,
    NODE_WAITING_HUMAN,
    AGENT_STARTED,
    AGENT_INTERRUPTED,
    AGENT_RESUMED,
    AGENT_COMPLETED,
    AGENT_FAILED,
    APPROVAL_REQUESTED,
    APPROVAL_SUBMITTED,
    APPROVAL_EXPIRED,
    REPORT_GENERATED
}
