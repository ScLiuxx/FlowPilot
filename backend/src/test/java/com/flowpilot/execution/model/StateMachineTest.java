package com.flowpilot.execution.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests of the state machines, which are the business rule centre of the execution engine.
 */
class StateMachineTest {

    @Test
    @DisplayName("allows the normal node lifecycle")
    void allowsNormalNodeLifecycle() {
        assertThat(NodeStatus.canTransition(NodeStatus.PENDING, NodeStatus.READY)).isTrue();
        assertThat(NodeStatus.canTransition(NodeStatus.READY, NodeStatus.RUNNING)).isTrue();
        assertThat(NodeStatus.canTransition(NodeStatus.RUNNING, NodeStatus.SUCCEEDED)).isTrue();
    }

    @Test
    @DisplayName("allows a failed node to be retried")
    void allowsRetry() {
        assertThat(NodeStatus.canTransition(NodeStatus.RUNNING, NodeStatus.RETRY_WAIT)).isTrue();
        assertThat(NodeStatus.canTransition(NodeStatus.RETRY_WAIT, NodeStatus.READY)).isTrue();
    }

    @Test
    @DisplayName("rejects a transition out of a terminal state")
    void rejectsTransitionFromTerminalState() {
        assertThat(NodeStatus.canTransition(NodeStatus.SUCCEEDED, NodeStatus.RUNNING)).isFalse();
        assertThat(NodeStatus.canTransition(NodeStatus.FAILED, NodeStatus.READY)).isFalse();
        assertThat(NodeStatus.canTransition(NodeStatus.CANCELED, NodeStatus.RUNNING)).isFalse();
        assertThat(NodeStatus.canTransition(NodeStatus.SKIPPED, NodeStatus.READY)).isFalse();
    }

    @Test
    @DisplayName("rejects skipping the running state")
    void rejectsSkippingRunningState() {
        assertThat(NodeStatus.canTransition(NodeStatus.READY, NodeStatus.SUCCEEDED)).isFalse();
        assertThat(NodeStatus.canTransition(NodeStatus.PENDING, NodeStatus.SUCCEEDED)).isFalse();
    }

    @Test
    @DisplayName("treats the human waiting state as a normal pause")
    void treatsHumanWaitAsNormalPause() {
        assertThat(NodeStatus.canTransition(NodeStatus.RUNNING, NodeStatus.WAITING_HUMAN)).isTrue();
        assertThat(NodeStatus.canTransition(NodeStatus.WAITING_HUMAN, NodeStatus.READY)).isTrue();
        assertThat(NodeStatus.WAITING_HUMAN.isTerminal()).isFalse();
        assertThat(NodeStatus.WAITING_HUMAN.satisfiesDependency()).isFalse();
    }

    @Test
    @DisplayName("only a succeeded node satisfies a downstream dependency")
    void onlySucceededSatisfiesDependency() {
        assertThat(NodeStatus.SUCCEEDED.satisfiesDependency()).isTrue();
        assertThat(NodeStatus.FAILED.satisfiesDependency()).isFalse();
        assertThat(NodeStatus.SKIPPED.satisfiesDependency()).isFalse();
        assertThat(NodeStatus.CANCELED.satisfiesDependency()).isFalse();
    }

    @Test
    @DisplayName("allows the normal workflow lifecycle")
    void allowsNormalWorkflowLifecycle() {
        assertThat(WorkflowStatus.canTransition(WorkflowStatus.CREATED, WorkflowStatus.QUEUED)).isTrue();
        assertThat(WorkflowStatus.canTransition(WorkflowStatus.QUEUED, WorkflowStatus.RUNNING)).isTrue();
        assertThat(WorkflowStatus.canTransition(WorkflowStatus.RUNNING, WorkflowStatus.SUCCEEDED)).isTrue();
        assertThat(WorkflowStatus.canTransition(WorkflowStatus.RUNNING, WorkflowStatus.FAILED)).isTrue();
    }

    @Test
    @DisplayName("allows a workflow to pause for a human and to continue afterwards")
    void allowsHumanPauseAndResume() {
        assertThat(WorkflowStatus.canTransition(WorkflowStatus.RUNNING, WorkflowStatus.WAITING_HUMAN)).isTrue();
        assertThat(WorkflowStatus.canTransition(WorkflowStatus.WAITING_HUMAN, WorkflowStatus.RUNNING)).isTrue();
        assertThat(WorkflowStatus.canTransition(WorkflowStatus.WAITING_HUMAN, WorkflowStatus.SUCCEEDED)).isTrue();
        assertThat(WorkflowStatus.WAITING_HUMAN.isTerminal()).isFalse();
    }

    @Test
    @DisplayName("allows a workflow to wait for a retry")
    void allowsRetryWait() {
        assertThat(WorkflowStatus.canTransition(WorkflowStatus.RUNNING, WorkflowStatus.RETRY_WAIT)).isTrue();
        assertThat(WorkflowStatus.canTransition(WorkflowStatus.RETRY_WAIT, WorkflowStatus.RUNNING)).isTrue();
    }

    @Test
    @DisplayName("rejects a transition out of a terminal workflow state")
    void rejectsTerminalWorkflowTransition() {
        assertThat(WorkflowStatus.canTransition(WorkflowStatus.SUCCEEDED, WorkflowStatus.RUNNING)).isFalse();
        assertThat(WorkflowStatus.canTransition(WorkflowStatus.FAILED, WorkflowStatus.RUNNING)).isFalse();
        assertThat(WorkflowStatus.canTransition(WorkflowStatus.CANCELED, WorkflowStatus.RUNNING)).isFalse();
    }

    @Test
    @DisplayName("rejects a transition from an unknown state")
    void rejectsUnknownState() {
        assertThat(WorkflowStatus.canTransition(null, WorkflowStatus.RUNNING)).isFalse();
        assertThat(NodeStatus.canTransition(null, NodeStatus.READY)).isFalse();
    }
}
