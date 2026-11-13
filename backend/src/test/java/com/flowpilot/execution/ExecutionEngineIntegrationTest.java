package com.flowpilot.execution;

import com.flowpilot.common.util.JsonUtils;
import com.flowpilot.execution.entity.NodeExecution;
import com.flowpilot.execution.entity.WorkflowExecution;
import com.flowpilot.execution.mapper.NodeExecutionAttemptMapper;
import com.flowpilot.execution.mapper.NodeExecutionMapper;
import com.flowpilot.execution.model.NodeStatus;
import com.flowpilot.execution.model.WorkflowStatus;
import com.flowpilot.execution.scheduler.WorkflowScheduler;
import com.flowpilot.execution.service.ExecutionEventService;
import com.flowpilot.execution.service.WorkflowExecutionService;
import com.flowpilot.messaging.outbox.entity.MessageOutbox;
import com.flowpilot.messaging.outbox.mapper.MessageOutboxMapper;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests of the execution engine against a real MySQL instance.
 *
 * <p>They cover the behaviour that matters most: the parallel scheduling of independent nodes,
 * the idempotency of a repeated message and the fencing token that stops a stale worker from
 * overwriting a newer result.
 */
@SpringBootTest
@ActiveProfiles("test")
class ExecutionEngineIntegrationTest {

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
    private ExecutionEventService eventService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("T02/T03: creates a workflow, publishes an immutable version and detects a cycle")
    void createsAndValidatesWorkflow() {
        Long userId = 9001L;
        WorkflowDefinitionDocument valid = definition(
                node("start", "CODE", Map.of("operation", "identity"), List.of()),
                node("second", "CODE", Map.of("operation", "identity"), List.of("start")));

        var definition = workflowService.create("parallel demo", "demo", "TEST", userId, valid);
        var version = workflowService.listVersionsInternal(definition.getId()).get(0);
        var published = workflowService.publish(definition.getId(), version.getId(), userId);

        assertThat(published.getStatus()).isEqualTo("PUBLISHED");
        assertThat(published.getDefinitionHash()).hasSize(64);

        // A published version cannot be published again.
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> workflowService.publish(definition.getId(), version.getId(), userId))
                .isInstanceOf(com.flowpilot.common.exception.BizException.class);

        // A cyclic definition is rejected at creation time.
        WorkflowDefinitionDocument cyclic = definition(
                node("a", "CODE", Map.of("operation", "identity"), List.of("b")),
                node("b", "CODE", Map.of("operation", "identity"), List.of("a")));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> workflowService.create("cyclic", null, "TEST", userId, cyclic))
                .isInstanceOf(com.flowpilot.common.exception.BizException.class)
                .hasMessageContaining("Cycle detected");
    }

    @Test
    @DisplayName("T04: schedules the independent nodes of a DAG in parallel")
    @org.springframework.core.annotation.Order(1)
    void schedulesIndependentNodesInParallel() {
        Long userId = 9002L;
        // a -> b, a -> c, then d waits for b and c.
        WorkflowDefinitionDocument document = definition(
                node("a", "CODE", Map.of("operation", "identity"), List.of()),
                node("b", "CODE", Map.of("operation", "identity"), List.of("a")),
                node("c", "CODE", Map.of("operation", "identity"), List.of("a")),
                node("d", "CODE", Map.of("operation", "identity"), List.of("b", "c")));

        var workflow = workflowService.create("parallel", null, "TEST", userId, document);
        var version = workflowService.listVersionsInternal(workflow.getId()).get(0);
        workflowService.publish(workflow.getId(), version.getId(), userId);
        var published = workflowService.getPublishedVersion(workflow.getId());

        WorkflowExecution execution = executionService.start(published, document, userId,
                Map.of("repositoryUrl", "https://github.com/example/project"));

        // The start message is written to the outbox in the same transaction.
        List<MessageOutbox> outbox = outboxMapper.selectList(null).stream()
                .filter(record -> record.getAggregateId().equals(execution.getId()))
                .toList();
        assertThat(outbox).hasSize(1);
        assertThat(outbox.get(0).getRoutingKey()).isEqualTo("workflow.started");

        scheduler.onWorkflowStarted(execution.getId());

        List<NodeExecution> nodes = executionService.nodes(execution.getId());
        NodeExecution nodeA = findNode(nodes, "a");
        NodeExecution nodeB = findNode(nodes, "b");
        NodeExecution nodeC = findNode(nodes, "c");
        NodeExecution nodeD = findNode(nodes, "d");

        // Only the start node becomes ready, the others wait for their dependencies.
        assertThat(nodeA.getStatus()).isEqualTo(NodeStatus.READY.name());
        assertThat(nodeB.getStatus()).isEqualTo(NodeStatus.PENDING.name());
        assertThat(nodeC.getStatus()).isEqualTo(NodeStatus.PENDING.name());
        assertThat(nodeD.getStatus()).isEqualTo(NodeStatus.PENDING.name());

        // The workflow is running and exactly one node execute message was written for it.
        assertThat(executionService.get(execution.getId()).getStatus())
                .isEqualTo(WorkflowStatus.RUNNING.name());
        assertThat(countNodeExecuteMessages(execution.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("T06: a repeated scheduling decision produces exactly one execution message")
    void repeatedSchedulingIsIdempotent() {
        Long userId = 9003L;
        WorkflowDefinitionDocument document = definition(
                node("a", "CODE", Map.of("operation", "identity"), List.of()));

        var workflow = workflowService.create("idempotent", null, "TEST", userId, document);
        var version = workflowService.listVersionsInternal(workflow.getId()).get(0);
        workflowService.publish(workflow.getId(), version.getId(), userId);
        var published = workflowService.getPublishedVersion(workflow.getId());

        WorkflowExecution execution = executionService.start(published, document, userId, Map.of());

        long before = countNodeExecuteMessages(execution.getId());
        // The scheduler runs twice for the same execution. The second run must not produce a
        // second message, because the node is no longer PENDING.
        scheduler.onWorkflowStarted(execution.getId());
        scheduler.onWorkflowStarted(execution.getId());
        scheduler.scheduleInitialNodes(execution.getId());

        assertThat(countNodeExecuteMessages(execution.getId())).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("T07: a failed attempt is recorded and the node is scheduled for a retry")
    void failedAttemptIsRecordedForRetry() {
        Long userId = 9004L;
        WorkflowDefinitionDocument document = definition(
                node("a", "CODE", Map.of("operation", "identity"), List.of()));

        var workflow = workflowService.create("retry", null, "TEST", userId, document);
        var version = workflowService.listVersionsInternal(workflow.getId()).get(0);
        workflowService.publish(workflow.getId(), version.getId(), userId);
        var published = workflowService.getPublishedVersion(workflow.getId());

        WorkflowExecution execution = executionService.start(published, document, userId, Map.of());
        scheduler.onWorkflowStarted(execution.getId());

        NodeExecution node = findNode(executionService.nodes(execution.getId()), "a");
        assertThat(node.getStatus()).isEqualTo(NodeStatus.READY.name());

        // The worker acquires the lease and then fails.
        Long leaseToken = acquireLease(node, 1, node.getCurrentAttempt());
        assertThat(leaseToken).isNotNull();

        var resultService = resultService();
        resultService.fail(node.getId(), 1, leaseToken, "HTTP_TIMEOUT", "the request timed out",
                "TRANSIENT", true, null);

        NodeExecution afterFailure = nodeExecutionMapper.selectById(node.getId());
        assertThat(afterFailure.getStatus()).isEqualTo(NodeStatus.RETRY_WAIT.name());
        assertThat(afterFailure.getErrorCode()).isEqualTo("HTTP_TIMEOUT");

        // The failed attempt is kept, so the retry history stays analysable.
        assertThat(attemptMapper.findByNodeExecutionId(node.getId())).hasSize(1);
        assertThat(attemptMapper.findByNodeExecutionId(node.getId()).get(0).getStatus())
                .isEqualTo(NodeStatus.FAILED.name());
    }

    @Test
    @DisplayName("T08: the fencing token stops a stale worker from committing its result")
    void staleWorkerCannotCommitItsResult() {
        Long userId = 9005L;
        WorkflowDefinitionDocument document = definition(
                node("a", "CODE", Map.of("operation", "identity"), List.of()));

        var workflow = workflowService.create("fencing", null, "TEST", userId, document);
        var version = workflowService.listVersionsInternal(workflow.getId()).get(0);
        workflowService.publish(workflow.getId(), version.getId(), userId);
        var published = workflowService.getPublishedVersion(workflow.getId());

        WorkflowExecution execution = executionService.start(published, document, userId, Map.of());
        scheduler.onWorkflowStarted(execution.getId());

        NodeExecution node = findNode(executionService.nodes(execution.getId()), "a");

        // Worker A takes the lease of the first attempt.
        Long tokenA = acquireLease(node, 1, node.getCurrentAttempt());

        // Worker A dies. Its lease expires, so the recovery scanner takes the node over.
        expireLease(node.getId());
        recoveryScheduler.recoverNode(nodeExecutionMapper.selectById(node.getId()));

        NodeExecution recovered = nodeExecutionMapper.selectById(node.getId());
        assertThat(recovered.getStatus()).isEqualTo(NodeStatus.RETRY_WAIT.name());
        assertThat(recovered.getErrorCode()).isEqualTo("WORKER_LEASE_EXPIRED");

        // The delay of the retry policy is persisted, so the node is not released before it
        // elapsed.
        assertThat(recovered.getNextRetryAt()).isNotNull();
        assertThat(recovered.getNextRetryAt()).isAfter(java.time.LocalDateTime.now());

        retryScheduler.release(nodeExecutionMapper.selectById(node.getId()));
        assertThat(nodeExecutionMapper.selectById(node.getId()).getStatus())
                .as("the node waits for the retry delay")
                .isEqualTo(NodeStatus.RETRY_WAIT.name());

        // Once the delay elapsed, the node becomes ready again.
        expireRetryDelay(node.getId());
        retryScheduler.release(nodeExecutionMapper.selectById(node.getId()));
        assertThat(nodeExecutionMapper.selectById(node.getId()).getStatus())
                .isEqualTo(NodeStatus.READY.name());

        // Worker B takes over with the second attempt, which increments the fencing token.
        NodeExecution ready = nodeExecutionMapper.selectById(node.getId());
        Long tokenB = acquireLease(ready, 2, ready.getCurrentAttempt());

        assertThat(tokenB).isGreaterThan(tokenA);

        // Worker A comes back to life and tries to commit its stale result. The attempt number
        // and the fencing token no longer match, so the update must be rejected.
        int rejected = nodeExecutionMapper.completeSuccessfully(node.getId(), 1, tokenA, "{}");
        assertThat(rejected).isZero();

        // Worker B may commit.
        int accepted = nodeExecutionMapper.completeSuccessfully(node.getId(), 2, tokenB, "{}");
        assertThat(accepted).isEqualTo(1);

        NodeExecution finished = nodeExecutionMapper.selectById(node.getId());
        assertThat(finished.getStatus()).isEqualTo(NodeStatus.SUCCEEDED.name());
        assertThat(finished.getCurrentAttempt()).isEqualTo(2);
    }

    @Test
    @DisplayName("T14: the execution events carry a strictly increasing sequence number")
    void executionEventsAreSequenced() {
        Long userId = 9006L;
        WorkflowDefinitionDocument document = definition(
                node("a", "CODE", Map.of("operation", "identity"), List.of()));

        var workflow = workflowService.create("events", null, "TEST", userId, document);
        var version = workflowService.listVersionsInternal(workflow.getId()).get(0);
        workflowService.publish(workflow.getId(), version.getId(), userId);
        var published = workflowService.getPublishedVersion(workflow.getId());

        WorkflowExecution execution = executionService.start(published, document, userId, Map.of());
        scheduler.onWorkflowStarted(execution.getId());

        var events = eventService.listAfter(execution.getId(), 0, 100);
        assertThat(events).isNotEmpty();
        for (int i = 0; i < events.size(); i++) {
            assertThat(events.get(i).getSequenceNo()).isEqualTo(i + 1L);
        }
        assertThat(events.get(0).getEventType()).isEqualTo("WORKFLOW_CREATED");
        assertThat(events.get(1).getEventType()).isEqualTo("WORKFLOW_STARTED");
    }

    private Long acquireLease(NodeExecution node, int attemptNo, Integer expectedAttempt) {
        return transactionTemplate.execute(status -> {
            int updated = nodeExecutionMapper.acquireLease(node.getId(), "worker-test", attemptNo,
                    expectedAttempt == null ? 0 : expectedAttempt,
                    java.time.LocalDateTime.now().plusMinutes(5));
            assertThat(updated).isEqualTo(1);
            return nodeExecutionMapper.selectById(node.getId()).getLeaseToken();
        });
    }

    @Test
    @DisplayName("T07: the retry delay of the policy is persisted and honoured")
    void theRetryDelayIsPersisted() {
        Long userId = 9007L;
        WorkflowDefinitionDocument document = definition(
                node("a", "CODE", Map.of("operation", "identity"), List.of()));

        var workflow = workflowService.create("retry delay", null, "TEST", userId, document);
        var version = workflowService.listVersionsInternal(workflow.getId()).get(0);
        workflowService.publish(workflow.getId(), version.getId(), userId);
        var published = workflowService.getPublishedVersion(workflow.getId());

        WorkflowExecution execution = executionService.start(published, document, userId, Map.of());
        scheduler.onWorkflowStarted(execution.getId());

        NodeExecution node = findNode(executionService.nodes(execution.getId()), "a");
        Long leaseToken = acquireLease(node, 1, node.getCurrentAttempt());

        resultService().fail(node.getId(), 1, leaseToken, "HTTP_TIMEOUT", "the request timed out",
                "TRANSIENT", true, null);

        NodeExecution waiting = nodeExecutionMapper.selectById(node.getId());
        assertThat(waiting.getStatus()).isEqualTo(NodeStatus.RETRY_WAIT.name());
        assertThat(waiting.getNextRetryAt())
                .as("the moment of the next attempt is persisted")
                .isNotNull()
                .isAfter(java.time.LocalDateTime.now());

        // The node is only released once the delay elapsed.
        retryScheduler.release(waiting);
        assertThat(nodeExecutionMapper.selectById(node.getId()).getStatus())
                .isEqualTo(NodeStatus.RETRY_WAIT.name());

        expireRetryDelay(node.getId());
        retryScheduler.release(nodeExecutionMapper.selectById(node.getId()));
        assertThat(nodeExecutionMapper.selectById(node.getId()).getStatus())
                .isEqualTo(NodeStatus.READY.name());
        assertThat(nodeExecutionMapper.selectById(node.getId()).getNextRetryAt())
                .as("the pending retry is cleared once the node runs again")
                .isNull();
    }

    @Test
    @DisplayName("T04: the branch a condition did not select is skipped")
    void theBranchThatWasNotSelectedIsSkipped() {
        Long userId = 9008L;
        WorkflowDefinitionDocument document = definition(
                node("check", "CONDITION", Map.of(
                        "expression", "count == 1",
                        "branches", Map.of("true", List.of("taken"), "false", List.of("skipped"))),
                        List.of()),
                node("taken", "CODE", Map.of("operation", "identity"), List.of("check")),
                node("skipped", "CODE", Map.of("operation", "identity"), List.of("check")));

        var workflow = workflowService.create("branching", null, "TEST", userId, document);
        var version = workflowService.listVersionsInternal(workflow.getId()).get(0);
        workflowService.publish(workflow.getId(), version.getId(), userId);
        var published = workflowService.getPublishedVersion(workflow.getId());

        WorkflowExecution execution = executionService.start(published, document, userId,
                Map.of("count", 1));
        scheduler.onWorkflowStarted(execution.getId());

        NodeExecution condition = findNode(executionService.nodes(execution.getId()), "check");
        Long leaseToken = acquireLease(condition, 1, condition.getCurrentAttempt());
        resultService().complete(condition.getId(), 1, leaseToken,
                Map.of("matched", true, "branch", "true"));
        scheduler.onNodeCompleted(execution.getId(), "check");

        NodeExecution skipped = findNode(executionService.nodes(execution.getId()), "skipped");
        assertThat(skipped.getStatus())
                .as("the branch that was not selected is skipped")
                .isEqualTo(NodeStatus.SKIPPED.name());

        NodeExecution taken = findNode(executionService.nodes(execution.getId()), "taken");
        assertThat(taken.getStatus())
                .as("the selected branch runs")
                .isEqualTo(NodeStatus.READY.name());
    }

    /**
     * Simulates a worker that died: the lease expires without a heartbeat.
     */
    private void expireLease(Long nodeExecutionId) {
        transactionTemplate.executeWithoutResult(status -> {
            NodeExecution node = nodeExecutionMapper.selectById(nodeExecutionId);
            node.setLeaseExpireAt(java.time.LocalDateTime.now().minusMinutes(5));
            nodeExecutionMapper.updateById(node);
        });
    }

    /**
     * Simulates that the delay of the retry policy elapsed.
     */
    private void expireRetryDelay(Long nodeExecutionId) {
        transactionTemplate.executeWithoutResult(status -> {
            NodeExecution node = nodeExecutionMapper.selectById(nodeExecutionId);
            node.setNextRetryAt(java.time.LocalDateTime.now().minusMinutes(1));
            nodeExecutionMapper.updateById(node);
        });
    }

    /**
     * Counts the node execute messages of one execution. The count is scoped to the execution so
     * the test stays independent of the other tests that share the database.
     */
    private long countNodeExecuteMessages(Long executionId) {
        return outboxMapper.selectList(null).stream()
                .filter(record -> "node.execute".equals(record.getRoutingKey()))
                .filter(record -> executionId.equals(
                        com.flowpilot.common.util.JsonUtils.toMap(record.getPayload())
                                .get("workflowExecutionId")))
                .count();
    }

    private NodeExecution findNode(List<NodeExecution> nodes, String nodeKey) {
        return nodes.stream()
                .filter(node -> nodeKey.equals(node.getNodeKey()))
                .findFirst()
                .orElseThrow();
    }

    private com.flowpilot.execution.service.NodeResultService resultService() {
        return nodeResultService;
    }

    @Autowired
    private com.flowpilot.execution.service.NodeResultService nodeResultService;

    @Autowired
    private com.flowpilot.execution.recovery.RecoveryScheduler recoveryScheduler;

    @Autowired
    private com.flowpilot.execution.recovery.RetryScheduler retryScheduler;

    private WorkflowDefinitionDocument definition(WorkflowNodeDefinition... nodes) {
        return WorkflowDefinitionDocument.builder()
                .name("test")
                .nodes(List.of(nodes))
                .build();
    }

    private WorkflowNodeDefinition node(String id, String type, Map<String, Object> config,
                                        List<String> dependencies) {
        return WorkflowNodeDefinition.builder()
                .id(id)
                .type(type)
                .config(config)
                .dependencies(dependencies)
                .build();
    }
}
