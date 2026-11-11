package com.flowpilot.execution.model;

import java.util.Set;

/**
 * Workflow execution lifecycle.
 *
 * <p>{@code WAITING_HUMAN} is a normal pause, not a failure. A workflow that waits for an
 * approval for two hours must not be treated as a timeout.
 */
public enum WorkflowStatus {

    /** Execution instance created, nodes not initialised yet. */
    CREATED,
    /** Waiting for the scheduler to pick it up. */
    QUEUED,
    /** At least one node is running. */
    RUNNING,
    /** Paused, waiting for a human decision. */
    WAITING_HUMAN,
    /** A node failed and will be retried after a delay. */
    RETRY_WAIT,
    /** All required nodes succeeded. */
    SUCCEEDED,
    /** An unrecoverable failure occurred. */
    FAILED,
    /** The user cancelled the execution. */
    CANCELED;

    private static final java.util.Map<WorkflowStatus, Set<WorkflowStatus>> TRANSITIONS = java.util.Map.of(
            CREATED, Set.of(QUEUED, RUNNING, CANCELED, FAILED),
            QUEUED, Set.of(RUNNING, RETRY_WAIT, FAILED, CANCELED),
            RUNNING, Set.of(WAITING_HUMAN, RETRY_WAIT, SUCCEEDED, FAILED, CANCELED),
            WAITING_HUMAN, Set.of(RUNNING, SUCCEEDED, FAILED, CANCELED),
            RETRY_WAIT, Set.of(RUNNING, WAITING_HUMAN, SUCCEEDED, FAILED, CANCELED),
            SUCCEEDED, Set.of(),
            FAILED, Set.of(),
            CANCELED, Set.of());

    public static boolean canTransition(WorkflowStatus from, WorkflowStatus to) {
        if (from == null || to == null) {
            return false;
        }
        return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    public static Set<WorkflowStatus> allowedTargets(WorkflowStatus from) {
        return TRANSITIONS.getOrDefault(from, Set.of());
    }

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELED;
    }

    public boolean isActive() {
        return this == RUNNING || this == QUEUED || this == RETRY_WAIT;
    }
}
