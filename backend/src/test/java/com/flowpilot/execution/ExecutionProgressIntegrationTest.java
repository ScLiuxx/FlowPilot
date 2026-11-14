package com.flowpilot.execution;

import com.flowpilot.execution.entity.NodeExecution;
import com.flowpilot.execution.entity.WorkflowExecution;
import com.flowpilot.execution.mapper.NodeExecutionMapper;
import com.flowpilot.execution.mapper.WorkflowExecutionMapper;
import com.flowpilot.execution.model.NodeStatus;
import com.flowpilot.execution.recovery.RecoveryScheduler;
import com.flowpilot.execution.scheduler.WorkflowScheduler;
import com.flowpilot.execution.service.NodeResultService;
import com.flowpilot.execution.service.WorkflowExecutionService;
import com.flowpilot.workflow.definition.WorkflowDefinitionDocument;
import com.flowpilot.workflow.definition.WorkflowNodeDefinition;
import com.flowpilot.workflow.entity.WorkflowVersion;
import com.flowpilot.workflow.service.WorkflowService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests of the progress of a running workflow.
 *
 * <p>The console shows how far a workflow got. The counters must therefore follow the states of
 * the nodes while the workflow runs and not only when it finishes.
 */
@SpringBootTest
@ActiveProfiles("test")
class ExecutionProgressIntegrationTest {

    @Autowired
    private WorkflowService workflowService;
    @Autowired
    private WorkflowExecutionService executionService;
    @Autowired
    private WorkflowScheduler scheduler;
    @Autowired
    private NodeExecutionMapper nodeExecutionMapper;
    @Autowired
    private WorkflowExecutionMapper executionMapper;
    @Autowired
    private NodeResultService resultService;
    @Autowired
    private RecoveryScheduler recoveryScheduler;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("the counters of a running workflow follow the nodes that finished")
    void theCountersFollowTheNodes() {
        Long executionId = startWorkflow(9701L, "progress demo");
        NodeExecution first = nodeOf(executionId, "first");

        // The node runs and finishes.
        transactionTemplate.executeWithoutResult(status -> nodeExecutionMapper.acquireLease(
                first.getId(), "worker-test", 1, 0, LocalDateTime.now().plusMinutes(5)));
        NodeExecution leased = nodeExecutionMapper.selectById(first.getId());
        resultService.complete(leased.getId(), 1, leased.getLeaseToken(), Map.of("value", 1));
        // The scheduler reacts to the completion event and refreshes the counters.
        scheduler.checkCompletion(executionId);

        WorkflowExecution execution = executionMapper.selectById(executionId);
        assertThat(execution.getCompletedNodeCount()).isEqualTo(1);
        assertThat(execution.getFailedNodeCount()).isZero();
        // The workflow is still running: the second node waits for its predecessor.
        assertThat(execution.getStatus()).isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("a node that failed for good is counted as failed while the workflow runs")
    void aFailedNodeIsCounted() {
        Long executionId = startWorkflow(9702L, "failed node demo");
        NodeExecution first = nodeOf(executionId, "first");

        transactionTemplate.executeWithoutResult(status -> nodeExecutionMapper.acquireLease(
                first.getId(), "worker-test", 1, 0, LocalDateTime.now().plusMinutes(5)));
        NodeExecution leased = nodeExecutionMapper.selectById(first.getId());
        resultService.fail(leased.getId(), 1, leased.getLeaseToken(),
                "BOOM", "the node failed", "BUSINESS", false, null);
        scheduler.checkCompletion(executionId);

        WorkflowExecution execution = executionMapper.selectById(executionId);
        assertThat(execution.getFailedNodeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a ready node does not keep the worker of a previous attempt")
    void aReadyNodeHasNoWorker() {
        Long executionId = startWorkflow(9703L, "stale worker demo");
        NodeExecution first = nodeOf(executionId, "first");

        // The node runs and waits for its retry: the worker of the attempt is released.
        transactionTemplate.executeWithoutResult(status -> nodeExecutionMapper.acquireLease(
                first.getId(), "dead-worker", 1, 0, LocalDateTime.now().minusMinutes(5)));
        transactionTemplate.executeWithoutResult(status -> nodeExecutionMapper.markRetryWait(
                first.getId(), 1, 1L, "FAILED", "the worker died", LocalDateTime.now()));

        NodeExecution waiting = nodeExecutionMapper.selectById(first.getId());
        assertThat(waiting.getStatus()).isEqualTo(NodeStatus.RETRY_WAIT.name());
        assertThat(waiting.getWorkerId()).isNullOrEmpty();

        // The retry releases the node, and the ready scanner can see it: a node that no worker
        // owns is the one the scanner reschedules.
        transactionTemplate.executeWithoutResult(status -> nodeExecutionMapper.markRetryReady(first.getId()));
        NodeExecution ready = nodeExecutionMapper.selectById(first.getId());
        assertThat(ready.getStatus()).isEqualTo(NodeStatus.READY.name());
        assertThat(ready.getWorkerId()).isNullOrEmpty();
    }

    @Test
    @DisplayName("an agent node whose lease expired is taken over again instead of staying stuck")
    void anAgentNodeIsTakenOver() {
        Long executionId = startAgentWorkflow(9704L, "agent recovery demo");
        NodeExecution agentNode = nodeOf(executionId, "agent");

        // The agent service owns the node and the worker died: the lease expired.
        expireLease(agentNode.getId());
        NodeExecution expired = nodeExecutionMapper.selectById(agentNode.getId());
        assertThat(expired.getStatus()).isEqualTo(NodeStatus.RUNNING.name());
        long tokenBefore = expired.getLeaseToken();

        recoveryScheduler.recoverNode(expired);

        // The node was taken over: it runs again under a new lease, so the worker that died
        // cannot commit its result any more.
        NodeExecution recovered = nodeExecutionMapper.selectById(agentNode.getId());
        assertThat(recovered.getStatus()).isEqualTo(NodeStatus.RUNNING.name());
        assertThat(recovered.getLeaseToken()).isGreaterThan(tokenBefore);

        // The agent was told to continue: the command was published again.
        long commands = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM message_outbox WHERE message_type = 'AgentExecute' "
                        + "AND JSON_EXTRACT(payload, '$.workflowExecutionId') = ?",
                Long.class, executionId);
        assertThat(commands).isEqualTo(2);
    }

    private Long startWorkflow(Long userId, String name) {
        WorkflowDefinitionDocument document = WorkflowDefinitionDocument.builder()
                .name("test")
                .nodes(List.of(
                        WorkflowNodeDefinition.builder()
                                .id("first").type("CODE")
                                .config(Map.of("operation", "identity"))
                                .dependencies(List.of())
                                .build(),
                        WorkflowNodeDefinition.builder()
                                .id("second").type("CODE")
                                .config(Map.of("operation", "identity"))
                                .dependencies(List.of("first"))
                                .build()))
                .build();
        return start(userId, name, document);
    }

    private Long startAgentWorkflow(Long userId, String name) {
        WorkflowDefinitionDocument document = WorkflowDefinitionDocument.builder()
                .name("test")
                .nodes(List.of(
                        WorkflowNodeDefinition.builder()
                                .id("agent").type("LLM")
                                .config(Map.of("agentType", "LLM"))
                                .dependencies(List.of())
                                .build()))
                .build();
        return start(userId, name, document);
    }

    private Long start(Long userId, String name, WorkflowDefinitionDocument document) {
        var workflow = workflowService.create(name, null, "TEST", userId, document);
        var version = workflowService.listVersionsInternal(workflow.getId()).get(0);
        workflowService.publish(workflow.getId(), version.getId(), userId);
        WorkflowVersion published = workflowService.getPublishedVersion(workflow.getId());

        WorkflowExecution execution = executionService.start(published, document, userId, Map.of());
        scheduler.onWorkflowStarted(execution.getId());
        return execution.getId();
    }

    /**
     * Lets the lease of a node expire, without changing the attempt it belongs to.
     */
    private void expireLease(Long nodeExecutionId) {
        jdbcTemplate.update("UPDATE node_execution SET lease_expire_at = ? WHERE id = ?",
                LocalDateTime.now().minusMinutes(5), nodeExecutionId);
    }

    private NodeExecution nodeOf(Long executionId, String nodeKey) {
        return executionService.nodes(executionId).stream()
                .filter(node -> nodeKey.equals(node.getNodeKey()))
                .findFirst()
                .orElseThrow();
    }
}
