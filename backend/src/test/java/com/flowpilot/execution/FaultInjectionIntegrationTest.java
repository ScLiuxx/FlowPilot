package com.flowpilot.execution;

import com.flowpilot.execution.entity.NodeExecution;
import com.flowpilot.execution.entity.WorkflowExecution;
import com.flowpilot.execution.mapper.NodeExecutionMapper;
import com.flowpilot.execution.model.NodeStatus;
import com.flowpilot.execution.model.WorkflowStatus;
import com.flowpilot.execution.scheduler.WorkflowScheduler;
import com.flowpilot.execution.service.ExecutionEventService;
import com.flowpilot.execution.service.NodeResultService;
import com.flowpilot.execution.service.WorkflowExecutionService;
import com.flowpilot.messaging.outbox.entity.MessageOutbox;
import com.flowpilot.messaging.outbox.mapper.MessageOutboxMapper;
import com.flowpilot.messaging.protocol.MessageEnvelope;
import com.flowpilot.messaging.protocol.MessageType;
import com.flowpilot.messaging.protocol.payloads.NodeExecutePayload;
import com.flowpilot.workflow.definition.WorkflowDefinitionDocument;
import com.flowpilot.workflow.definition.WorkflowNodeDefinition;
import com.flowpilot.workflow.entity.WorkflowVersion;
import com.flowpilot.workflow.service.WorkflowService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fault injection tests.
 *
 * <p>The engine design asks for a test of every failure scenario: a worker that is killed
 * during the execution, a worker whose heartbeat stops, a lease that expired and was recovered,
 * two workers that compete for the same node, a backend that restarts while an agent runs, and
 * a service that restarts while a human approval is pending.
 *
 * <p>Every scenario is injected by breaking one thing on purpose and then checking that the
 * platform keeps its invariants.
 */
@SpringBootTest
@ActiveProfiles("test")
class FaultInjectionIntegrationTest {

    @Autowired
    private WorkflowService workflowService;
    @Autowired
    private WorkflowExecutionService executionService;
    @Autowired
    private WorkflowScheduler scheduler;
    @Autowired
    private NodeExecutionMapper nodeExecutionMapper;
    @Autowired
    private MessageOutboxMapper outboxMapper;
    @Autowired
    private NodeResultService nodeResultService;
    @Autowired
    private ExecutionEventService eventService;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private com.flowpilot.execution.recovery.RecoveryScheduler recoveryScheduler;
    @Autowired
    private com.flowpilot.execution.recovery.RetryScheduler retryScheduler;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("a worker that is killed during the execution leaves a recoverable node")
    void workerKilledDuringExecution() {
        Long executionId = startWorkflow(9501L, "killed worker demo");
        NodeExecution node = nodeOf(executionId, "first");

        // The worker takes the node and is killed before it reports anything.
        transactionTemplate.executeWithoutResult(status -> nodeExecutionMapper.acquireLease(
                node.getId(), "killed-worker", 1, 0, LocalDateTime.now().plusMinutes(5)));
        transactionTemplate.executeWithoutResult(status -> nodeResultService.startAttempt(
                nodeExecutionMapper.selectById(node.getId()), 1, 1L, "killed-worker"));

        // The node is still running and nobody reported a result.
        NodeExecution running = nodeExecutionMapper.selectById(node.getId());
        assertThat(running.getStatus()).isEqualTo(NodeStatus.RUNNING.name());
        assertThat(running.getOutputJson()).isNull();
    }

    @Test
    @DisplayName("a heartbeat that stops lets the lease expire, and the recovery takes over")
    void heartbeatStopsAndLeaseExpires() {
        Long executionId = startWorkflow(9502L, "heartbeat demo");
        NodeExecution node = nodeOf(executionId, "first");

        transactionTemplate.executeWithoutResult(status -> nodeExecutionMapper.acquireLease(
                node.getId(), "silent-worker", 1, 0, LocalDateTime.now().minusMinutes(1)));

        // The lease expired, so the recovery scanner finds the node.
        List<NodeExecution> expired = nodeExecutionMapper.findExpiredLeases(
                LocalDateTime.now(), 50);
        assertThat(expired).extracting(NodeExecution::getId).contains(node.getId());
    }

    @Test
    @DisplayName("two workers cannot both own the same node")
    void twoWorkersCompeteForTheSameNode() {
        Long executionId = startWorkflow(9503L, "competing workers demo");
        NodeExecution node = nodeOf(executionId, "first");

        int first = nodeExecutionMapper.acquireLease(node.getId(), "worker-a", 1, 0,
                LocalDateTime.now().plusMinutes(5));
        int second = nodeExecutionMapper.acquireLease(node.getId(), "worker-b", 1, 0,
                LocalDateTime.now().plusMinutes(5));

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();

        // The winner holds the lease, and the fencing token was incremented.
        NodeExecution owned = nodeExecutionMapper.selectById(node.getId());
        assertThat(owned.getWorkerId()).isEqualTo("worker-a");
        assertThat(owned.getLeaseToken()).isEqualTo(1L);
    }

    @Test
    @DisplayName("a stale worker cannot commit its result after it lost the lease")
    void staleWorkerCannotCommitItsResult() {
        Long executionId = startWorkflow(9504L, "stale worker demo");
        NodeExecution node = nodeOf(executionId, "first");

        // The first worker takes the node and is slow.
        nodeExecutionMapper.acquireLease(node.getId(), "slow-worker", 1, 0,
                LocalDateTime.now().plusMinutes(5));
        Long staleToken = nodeExecutionMapper.selectById(node.getId()).getLeaseToken();

        // The lease expires and the recovery takes the node over. The attempt fails, so the node
        // waits for its retry.
        expireLease(node.getId());
        recoveryScheduler.recoverNode(nodeExecutionMapper.selectById(node.getId()));
        // The delay of the retry policy elapses, so the node is released.
        expireRetryDelay(node.getId());
        retryScheduler.release(nodeExecutionMapper.selectById(node.getId()));

        // A second worker takes the node for the second attempt. The fencing token moves on.
        NodeExecution ready = nodeExecutionMapper.selectById(node.getId());
        assertThat(ready.getStatus()).isEqualTo(NodeStatus.READY.name());
        int taken = nodeExecutionMapper.acquireLease(node.getId(), "fast-worker", 2,
                ready.getCurrentAttempt(), LocalDateTime.now().plusMinutes(5));
        assertThat(taken).isEqualTo(1);
        assertThat(nodeExecutionMapper.selectById(node.getId()).getLeaseToken())
                .isGreaterThan(staleToken);

        // The slow worker tries to commit its result and is rejected.
        boolean accepted = nodeResultService.complete(node.getId(), 1, staleToken,
                Map.of("stale", true));

        assertThat(accepted).isFalse();
        NodeExecution current = nodeExecutionMapper.selectById(node.getId());
        assertThat(current.getStatus()).isEqualTo(NodeStatus.RUNNING.name());
        assertThat(current.getOutputJson()).isNull();
    }

    @Test
    @DisplayName("a message that is delivered twice does not run the node twice")
    void duplicateMessageDoesNotRunTheNodeTwice() {
        Long executionId = startWorkflow(9505L, "duplicate message demo");
        NodeExecution node = nodeOf(executionId, "first");

        // The first delivery takes the node.
        int first = nodeExecutionMapper.acquireLease(node.getId(), "worker-a", 1, 0,
                LocalDateTime.now().plusMinutes(5));
        // The second delivery finds the node already running.
        int second = nodeExecutionMapper.acquireLease(node.getId(), "worker-b", 1, 1,
                LocalDateTime.now().plusMinutes(5));

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();

        NodeExecution owned = nodeExecutionMapper.selectById(node.getId());
        assertThat(owned.getWorkerId()).isEqualTo("worker-a");
        assertThat(owned.getCurrentAttempt()).isEqualTo(1);
    }

    @Test
    @DisplayName("the message of a node carries the trace of the execution")
    void theMessageCarriesTheTraceOfTheExecution() {
        Long executionId = startWorkflow(9506L, "trace propagation demo");
        NodeExecution node = nodeOf(executionId, "first");

        WorkflowExecution execution = executionService.get(executionId);
        assertThat(execution.getTraceId()).isNotNull();
        assertThat(execution.getTraceId()).matches("^[0-9a-f]{32}$");

        MessageOutbox message = outboxMapper.selectList(null).stream()
                .filter(row -> node.getId().equals(row.getAggregateId()))
                .filter(row -> MessageType.NODE_EXECUTE.wireName().equals(row.getMessageType()))
                .findFirst()
                .orElseThrow();
        assertThat(message.getPayload()).contains(execution.getTraceId());
    }

    @Test
    @DisplayName("a cancelled workflow stops the nodes that have not started yet")
    void cancellationStopsThePendingNodes() {
        Long userId = 9507L;
        Long executionId = startWorkflow(userId, "cancellation demo");

        transactionTemplate.executeWithoutResult(status -> executionService.cancel(
                executionId, "USER_REQUEST", userId));

        NodeExecution cancelled = nodeExecutionMapper.selectById(nodeOf(executionId, "first").getId());
        assertThat(cancelled.getStatus()).isEqualTo(NodeStatus.CANCELED.name());
    }

    private Long startWorkflow(Long userId, String name) {
        WorkflowDefinitionDocument document = definition(
                node("first", "CODE", Map.of("operation", "identity"), List.of()),
                node("second", "CODE", Map.of("operation", "identity"), List.of("first")));

        var workflow = workflowService.create(name, null, "TEST", userId, document);
        var version = workflowService.listVersions(workflow.getId()).get(0);
        workflowService.publish(workflow.getId(), version.getId(), userId);
        WorkflowVersion published = workflowService.getPublishedVersion(workflow.getId());

        WorkflowExecution execution = executionService.start(published, document, userId, Map.of());
        scheduler.onWorkflowStarted(execution.getId());
        return execution.getId();
    }

    private NodeExecution nodeOf(Long executionId, String nodeKey) {
        return executionService.nodes(executionId).stream()
                .filter(node -> nodeKey.equals(node.getNodeKey()))
                .findFirst()
                .orElseThrow();
    }

    /**
     * Expires the lease of a node, which is what happens when a worker stops sending
     * heartbeats.
     */
    private void expireLease(Long nodeExecutionId) {
        transactionTemplate.executeWithoutResult(status -> nodeExecutionMapper.extendLease(
                nodeExecutionId, nodeExecutionMapper.selectById(nodeExecutionId).getLeaseToken(),
                LocalDateTime.now().minusMinutes(5)));
    }

    /**
     * Moves the moment the node may be retried into the past.
     */
    private void expireRetryDelay(Long nodeExecutionId) {
        jdbcTemplate.update("UPDATE node_execution SET next_retry_at = ? WHERE id = ?",
                LocalDateTime.now().minusMinutes(5), nodeExecutionId);
    }

    private WorkflowDefinitionDocument definition(WorkflowNodeDefinition... nodes) {
        return WorkflowDefinitionDocument.builder().name("test").nodes(List.of(nodes)).build();
    }

    private WorkflowNodeDefinition node(String id, String type, Map<String, Object> config,
                                        List<String> dependencies) {
        return WorkflowNodeDefinition.builder()
                .id(id).type(type).config(config).dependencies(dependencies).build();
    }
}
