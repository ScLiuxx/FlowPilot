package com.flowpilot.execution.model;

import java.util.Set;

/**
 * Node execution lifecycle.
 *
 * <p>A node execution row represents one <em>logical</em> execution. Every physical attempt is
 * recorded separately in {@code node_execution_attempt}, so the retry history survives without
 * breaking the dependency graph.
 */
public enum NodeStatus {

    /** Dependencies are not satisfied yet. */
    PENDING,
    /** Dependencies are satisfied, the node is queued for execution. */
    READY,
    /** The node is being executed by a worker. */
    RUNNING,
    /** The attempt failed, the node waits for the retry delay to elapse. */
    RETRY_WAIT,
    /** The node paused and waits for human input. */
    WAITING_HUMAN,
    /** The node finished successfully. */
    SUCCEEDED,
    /** The node failed and will not be retried. */
    FAILED,
    /** The node was skipped because a condition selected another branch. */
    SKIPPED,
    /** The node was cancelled. */
    CANCELED;

    private static final java.util.Map<NodeStatus, Set<NodeStatus>> TRANSITIONS = java.util.Map.of(
            PENDING, Set.of(READY, SKIPPED, CANCELED, FAILED),
            READY, Set.of(RUNNING, SKIPPED, CANCELED, FAILED),
            RUNNING, Set.of(SUCCEEDED, FAILED, RETRY_WAIT, WAITING_HUMAN, CANCELED),
            RETRY_WAIT, Set.of(READY, CANCELED, FAILED),
            WAITING_HUMAN, Set.of(READY, RUNNING, CANCELED, FAILED),
            SUCCEEDED, Set.of(),
            FAILED, Set.of(),
            SKIPPED, Set.of(),
            CANCELED, Set.of());

    public static boolean canTransition(NodeStatus from, NodeStatus to) {
        if (from == null || to == null) {
            return false;
        }
        return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    public static Set<NodeStatus> allowedTargets(NodeStatus from) {
        return TRANSITIONS.getOrDefault(from, Set.of());
    }

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == SKIPPED || this == CANCELED;
    }

    /**
     * A terminal state that still satisfies the dependency of a downstream node.
     */
    public boolean satisfiesDependency() {
        return this == SUCCEEDED;
    }
}
