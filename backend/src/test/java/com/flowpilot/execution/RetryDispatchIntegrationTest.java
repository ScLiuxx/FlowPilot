package com.flowpilot.execution;

import com.flowpilot.common.util.JsonUtils;
import com.flowpilot.execution.entity.NodeExecution;
import com.flowpilot.execution.entity.WorkflowExecution;
import com.flowpilot.execution.mapper.NodeExecutionMapper;
import com.flowpilot.execution.model.NodeStatus;
import com.flowpilot.execution.recovery.RetryScheduler;
import com.flowpilot.execution.scheduler.WorkflowScheduler;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests of the dispatch of a retry.
 *
 * <p>The retry scheduler runs on a thread of its own, so there is no span to continue. The
 * message it publishes therefore carries no span id at all. The protocol accepts a missing span
 * id but not an empty one: a message with an empty span id was rejected by the agent service,
 * dropped into the dead letter queue and never delivered. The node then waited for ever.
 */
@SpringBootTest
@ActiveProfiles("test")
class RetryDispatchIntegrationTest {

    @Autowired
    private WorkflowService workflowService;
    @Autowired
    private WorkflowExecutionService executionService;
    @Autowired
    private WorkflowScheduler scheduler;
    @Autowired
    private RetryScheduler retryScheduler;
    @Autowired
    private NodeExecutionMapper nodeExecutionMapper;
    @Autowired
    private MessageOutboxMapper outboxMapper;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("the message of a retry carries no span id at all")
    void theMessageOfARetryCarriesNoSpanId() {
        Long userId = 9401L;
        Long executionId = startWorkflow(userId, "retry dispatch demo");
        NodeExecution node = nodeOf(executionId);

        // The node runs and fails, so it waits for its retry.
        transactionTemplate.executeWithoutResult(status -> nodeExecutionMapper.acquireLease(
                node.getId(), "worker-test", 1, 0, LocalDateTime.now().plusMinutes(5)));
        NodeExecution leased = nodeExecutionMapper.selectById(node.getId());
        transactionTemplate.executeWithoutResult(status -> nodeExecutionMapper.markRetryWait(
                node.getId(), 1, leased.getLeaseToken(), "BOOM", "the node failed",
                LocalDateTime.now().minusSeconds(1)));

        // The retry scheduler releases the node and the message is published again.
        retryScheduler.release(nodeExecutionMapper.selectById(node.getId()));

        MessageOutbox message = outboxMapper.selectList(null).stream()
                .filter(row -> "NodeExecute".equals(row.getMessageType()))
                .filter(row -> node.getId().equals(row.getAggregateId()))
                .reduce((first, second) -> second)
                .orElseThrow();

        Map<String, Object> envelope = JsonUtils.toMap(message.getPayload());
        assertThat(envelope.get("spanId"))
                .as("a message published outside a span carries no span id, never an empty one")
                .isNull();

        // The message conforms to the protocol, so the peer accepts it.
        assertThat(envelope.get("messageId")).isNotNull();
        assertThat((String) envelope.get("traceId")).matches("^[0-9a-f]{32}$");
    }

    @Test
    @DisplayName("the node is released for its next attempt")
    void theNodeIsReleasedForItsNextAttempt() {
        Long userId = 9402L;
        Long executionId = startWorkflow(userId, "retry release demo");
        NodeExecution node = nodeOf(executionId);

        transactionTemplate.executeWithoutResult(status -> nodeExecutionMapper.acquireLease(
                node.getId(), "worker-test", 1, 0, LocalDateTime.now().plusMinutes(5)));
        NodeExecution leased = nodeExecutionMapper.selectById(node.getId());
        transactionTemplate.executeWithoutResult(status -> nodeExecutionMapper.markRetryWait(
                node.getId(), 1, leased.getLeaseToken(), "BOOM", "the node failed",
                LocalDateTime.now().minusSeconds(1)));

        retryScheduler.release(nodeExecutionMapper.selectById(node.getId()));

        NodeExecution released = nodeExecutionMapper.selectById(node.getId());
        assertThat(released.getStatus()).isEqualTo(NodeStatus.READY.name());
        assertThat(released.getNextRetryAt()).isNull();
    }

    private Long startWorkflow(Long userId, String name) {
        WorkflowDefinitionDocument document = WorkflowDefinitionDocument.builder()
                .name("test")
                .nodes(List.of(
                        WorkflowNodeDefinition.builder()
                                .id("first").type("CODE")
                                .config(Map.of("operation", "identity"))
                                .dependencies(List.of())
                                .build()))
                .build();

        var workflow = workflowService.create(name, null, "TEST", userId, document);
        var version = workflowService.listVersionsInternal(workflow.getId()).get(0);
        workflowService.publish(workflow.getId(), version.getId(), userId);
        WorkflowVersion published = workflowService.getPublishedVersion(workflow.getId());

        WorkflowExecution execution = executionService.start(published, document, userId, Map.of());
        scheduler.onWorkflowStarted(execution.getId());
        return execution.getId();
    }

    private NodeExecution nodeOf(Long executionId) {
        return executionService.nodes(executionId).stream()
                .filter(node -> "first".equals(node.getNodeKey()))
                .findFirst()
                .orElseThrow();
    }
}
