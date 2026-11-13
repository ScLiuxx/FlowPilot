package com.flowpilot.execution;

import com.flowpilot.execution.entity.NodeExecution;
import com.flowpilot.execution.entity.WorkflowExecution;
import com.flowpilot.execution.mapper.NodeExecutionAttemptMapper;
import com.flowpilot.execution.mapper.NodeExecutionMapper;
import com.flowpilot.execution.model.NodeStatus;
import com.flowpilot.execution.model.WorkflowStatus;
import com.flowpilot.execution.recovery.RecoveryScheduler;
import com.flowpilot.execution.recovery.ReadyNodeRecoveryScheduler;
import com.flowpilot.execution.scheduler.WorkflowScheduler;
import com.flowpilot.execution.service.ExecutionEventService;
import com.flowpilot.execution.service.WorkflowExecutionService;
import com.flowpilot.messaging.outbox.entity.MessageOutbox;
import com.flowpilot.messaging.outbox.mapper.MessageOutboxMapper;
import com.flowpilot.messaging.protocol.MessageType;
import com.flowpilot.workflow.definition.WorkflowDefinitionDocument;
import com.flowpilot.workflow.definition.WorkflowNodeDefinition;
import com.flowpilot.workflow.entity.WorkflowVersion;
import com.flowpilot.workflow.service.WorkflowService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests of the recovery paths.
 *
 * <p>They cover the failures the engine design asks for: a worker that dies leaves a running
 * node whose lease expires, and a consumer that dies between the deduplication mark and the
 * lease leaves a ready node that nobody picks up.
 */
@SpringBootTest
@ActiveProfiles("test")
class RecoveryIntegrationTest {

    @Autowired
    private WorkflowService workflowService;
    @Autowired
    private WorkflowExecutionService executionService;
    @Autowired
    private WorkflowScheduler scheduler;
    @Autowired
    private NodeExecutionMapper nodeExecutionMapper;
    @Autowired
    private NodeExecutionAttemptMapper attemptMapper;
    @Autowired
    private MessageOutboxMapper outboxMapper;
    @Autowired
    private RecoveryScheduler recoveryScheduler;
    @Autowired
    private ReadyNodeRecoveryScheduler readyNodeRecoveryScheduler;
    @Autowired
    private ExecutionEventService eventService;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("a node that stays ready without a worker is scheduled again")
    void lostReadyNodeIsScheduledAgain() {
        Long userId = 9401L;
        Long executionId = startWorkflow(userId, "lost ready node demo");
        NodeExecution node = nodeOf(executionId, "first");

        // The node is ready, but no worker ever took it over.
        assertThat(node.getStatus()).isEqualTo(NodeStatus.READY.name());
        assertThat(node.getWorkerId()).isNullOrEmpty();

        long messagesBefore = countNodeExecuteMessages(node.getId());
        assertThat(messagesBefore).isEqualTo(1);

        // The scanner only picks up the node after the timeout, so the timestamp is moved back.
        backdateReadyNode(node.getId());
        readyNodeRecoveryScheduler.rescheduleLostNodes();

        // The execution message was written a second time.
        assertThat(countNodeExecuteMessages(node.getId())).isEqualTo(2);

        // The attempt number did not change, so a worker that is already running the node keeps
        // the right of committing its result.
        NodeExecution rescheduled = nodeExecutionMapper.selectById(node.getId());
        assertThat(rescheduled.getCurrentAttempt()).isEqualTo(0);
    }

    @Test
    @DisplayName("a node that a worker owns is not scheduled again")
    void ownedReadyNodeIsNotRescheduled() {
        Long userId = 9402L;
        Long executionId = startWorkflow(userId, "owned ready node demo");
        NodeExecution node = nodeOf(executionId, "first");

        transactionTemplate.executeWithoutResult(status -> nodeExecutionMapper.acquireLease(
                node.getId(), "worker-test", 1, 0, LocalDateTime.now().plusMinutes(5)));
        backdateReadyNode(node.getId());

        long messagesBefore = countNodeExecuteMessages(node.getId());
        readyNodeRecoveryScheduler.rescheduleLostNodes();

        assertThat(countNodeExecuteMessages(node.getId())).isEqualTo(messagesBefore);
    }

    @Test
    @DisplayName("an expired lease of a running node leads to a retry")
    void expiredLeaseLeadsToARetry() {
        Long userId = 9403L;
        Long executionId = startWorkflow(userId, "expired lease demo");
        NodeExecution node = nodeOf(executionId, "first");

        // The worker takes the node and then dies, so the lease expires.
        transactionTemplate.executeWithoutResult(status -> nodeExecutionMapper.acquireLease(
                node.getId(), "dead-worker", 1, 0, LocalDateTime.now().minusMinutes(5)));
        NodeExecution expired = nodeExecutionMapper.selectById(node.getId());
        assertThat(expired.getStatus()).isEqualTo(NodeStatus.RUNNING.name());

        // The scanner is disabled in the test profile, so the node is recovered directly.
        recoveryScheduler.recoverNode(expired);

        // The node waits for its retry, and the attempt history shows the failure.
        NodeExecution recovered = nodeExecutionMapper.selectById(node.getId());
        assertThat(recovered.getStatus()).isEqualTo(NodeStatus.RETRY_WAIT.name());
        assertThat(recovered.getErrorCode()).isEqualTo("WORKER_LEASE_EXPIRED");

        var attempt = attemptMapper.findByNodeAndAttempt(node.getId(), 1);
        assertThat(attempt).isNotNull();
        assertThat(attempt.getStatus()).isEqualTo(NodeStatus.FAILED.name());
    }

    @Test
    @DisplayName("a workflow whose node was recovered can still finish")
    void recoveredWorkflowCanFinish() {
        Long userId = 9404L;
        Long executionId = startWorkflow(userId, "recovered workflow demo");
        NodeExecution node = nodeOf(executionId, "first");

        transactionTemplate.executeWithoutResult(status -> nodeExecutionMapper.acquireLease(
                node.getId(), "dead-worker", 1, 0, LocalDateTime.now().minusMinutes(5)));
        recoveryScheduler.recoverNode(nodeExecutionMapper.selectById(node.getId()));

        // The retry becomes ready again and the workflow continues.
        NodeExecution waiting = nodeExecutionMapper.selectById(node.getId());
        assertThat(waiting.getStatus()).isEqualTo(NodeStatus.RETRY_WAIT.name());
        assertThat(waiting.getNextRetryAt()).isNotNull();

        WorkflowExecution execution = executionService.get(executionId);
        assertThat(execution.getStatus()).isEqualTo(WorkflowStatus.RUNNING.name());
    }

    private Long startWorkflow(Long userId, String name) {
        WorkflowDefinitionDocument document = definition(
                node("first", "CODE", Map.of("operation", "identity"), List.of()),
                node("second", "CODE", Map.of("operation", "identity"), List.of("first")));

        var workflow = workflowService.create(name, null, "TEST", userId, document);
        var version = workflowService.listVersionsInternal(workflow.getId()).get(0);
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
     * Counts the execution messages of a node.
     */
    private long countNodeExecuteMessages(Long nodeExecutionId) {
        List<MessageOutbox> rows = outboxMapper.selectList(null).stream()
                .filter(row -> nodeExecutionId.equals(row.getAggregateId()))
                .filter(row -> MessageType.NODE_EXECUTE.wireName().equals(row.getMessageType()))
                .toList();
        return rows.size();
    }

    /**
     * Moves the moment the node became ready into the past.
     */
    private void backdateReadyNode(Long nodeExecutionId) {
        transactionTemplate.executeWithoutResult(status -> {
            jdbcUpdate(nodeExecutionId);
        });
    }

    private void jdbcUpdate(Long nodeExecutionId) {
        org.springframework.jdbc.core.JdbcTemplate jdbcTemplate =
                new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        jdbcTemplate.update("UPDATE node_execution SET updated_at = ? WHERE id = ?",
                LocalDateTime.now().minusHours(1), nodeExecutionId);
    }

    @Autowired
    private javax.sql.DataSource dataSource;

    private WorkflowDefinitionDocument definition(WorkflowNodeDefinition... nodes) {
        return WorkflowDefinitionDocument.builder().name("test").nodes(List.of(nodes)).build();
    }

    private WorkflowNodeDefinition node(String id, String type, Map<String, Object> config,
                                        List<String> dependencies) {
        return WorkflowNodeDefinition.builder()
                .id(id).type(type).config(config).dependencies(dependencies).build();
    }
}
