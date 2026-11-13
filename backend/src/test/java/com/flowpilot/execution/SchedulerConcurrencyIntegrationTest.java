package com.flowpilot.execution;

import com.flowpilot.execution.entity.NodeExecution;
import com.flowpilot.execution.entity.WorkflowExecution;
import com.flowpilot.execution.mapper.NodeExecutionMapper;
import com.flowpilot.execution.scheduler.WorkflowScheduler;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests of the concurrency limit of a workflow.
 *
 * <p>The limit keeps a workflow from occupying the whole worker pool. A node whose dispatch the
 * limit blocks stays pending and has to be scheduled later, even though no node that finishes
 * later is its successor.
 */
@SpringBootTest(properties = "flowpilot.worker.max-concurrency=2")
@ActiveProfiles("test")
class SchedulerConcurrencyIntegrationTest {

    @Autowired
    private WorkflowService workflowService;
    @Autowired
    private WorkflowExecutionService executionService;
    @Autowired
    private WorkflowScheduler scheduler;
    @Autowired
    private NodeExecutionMapper nodeExecutionMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("a node that the concurrency limit held back is scheduled when a slot is free")
    void aNodeHeldBackByTheLimitIsScheduledLater() {
        Long executionId = startWorkflow(9605L, "concurrency demo");

        // The root node finishes, so its three successors may run. The limit allows two of them
        // at the same time.
        jdbcTemplate.update("UPDATE node_execution SET status = 'SUCCEEDED' "
                + "WHERE workflow_execution_id = ? AND node_key = 'root'", executionId);
        scheduler.onNodeCompleted(executionId, "root");

        assertThat(statusOf(executionId, "first")).isEqualTo("READY");
        assertThat(statusOf(executionId, "second")).isEqualTo("READY");
        assertThat(statusOf(executionId, "third")).isEqualTo("PENDING");

        // One of the two running nodes finishes: the third one is scheduled now, even though it
        // is not the successor of the node that finished.
        jdbcTemplate.update("UPDATE node_execution SET status = 'SUCCEEDED' "
                + "WHERE workflow_execution_id = ? AND node_key = 'first'", executionId);
        scheduler.onNodeCompleted(executionId, "first");

        assertThat(statusOf(executionId, "third")).isEqualTo("READY");
    }

    private String statusOf(Long executionId, String nodeKey) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM node_execution WHERE workflow_execution_id = ? AND node_key = ?",
                String.class, executionId, nodeKey);
    }

    private Long startWorkflow(Long userId, String name) {
        WorkflowDefinitionDocument document = WorkflowDefinitionDocument.builder()
                .name("test")
                .nodes(List.of(
                        WorkflowNodeDefinition.builder()
                                .id("root").type("CODE")
                                .config(Map.of("operation", "identity"))
                                .dependencies(List.of())
                                .build(),
                        WorkflowNodeDefinition.builder()
                                .id("first").type("CODE")
                                .config(Map.of("operation", "identity"))
                                .dependencies(List.of("root"))
                                .build(),
                        WorkflowNodeDefinition.builder()
                                .id("second").type("CODE")
                                .config(Map.of("operation", "identity"))
                                .dependencies(List.of("root"))
                                .build(),
                        WorkflowNodeDefinition.builder()
                                .id("third").type("CODE")
                                .config(Map.of("operation", "identity"))
                                .dependencies(List.of("root"))
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
}
