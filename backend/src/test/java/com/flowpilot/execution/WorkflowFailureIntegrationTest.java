package com.flowpilot.execution;

import com.flowpilot.execution.entity.WorkflowExecution;
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
 * Integration tests of the failure of a node.
 *
 * <p>A node that failed for good cannot have its successors run: they wait for a predecessor that
 * will never succeed. The workflow must therefore fail instead of waiting for them forever.
 */
@SpringBootTest
@ActiveProfiles("test")
class WorkflowFailureIntegrationTest {

    @Autowired
    private WorkflowService workflowService;
    @Autowired
    private WorkflowExecutionService executionService;
    @Autowired
    private WorkflowScheduler scheduler;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("a workflow whose node failed ends as failed instead of waiting forever")
    void aWorkflowWithAFailedNodeFails() {
        Long executionId = startWorkflow(9606L, "failure demo");

        // The root node failed for good, its successor was never started.
        jdbcTemplate.update("UPDATE node_execution SET status = 'FAILED' "
                + "WHERE workflow_execution_id = ? AND node_key = 'root'", executionId);

        scheduler.checkCompletion(executionId);

        assertThat(statusOf(executionId)).isEqualTo("FAILED");
        // The node that was never started is cancelled with the workflow.
        assertThat(statusOfNode(executionId, "child")).isEqualTo("CANCELED");
    }

    @Test
    @DisplayName("a workflow whose nodes all succeeded ends as succeeded")
    void aWorkflowWithAllNodesSucceededSucceeds() {
        Long executionId = startWorkflow(9607L, "success demo");

        jdbcTemplate.update("UPDATE node_execution SET status = 'SUCCEEDED' "
                + "WHERE workflow_execution_id = ? AND node_key = 'root'", executionId);
        jdbcTemplate.update("UPDATE node_execution SET status = 'SUCCEEDED' "
                + "WHERE workflow_execution_id = ? AND node_key = 'child'", executionId);

        scheduler.checkCompletion(executionId);

        assertThat(statusOf(executionId)).isEqualTo("SUCCEEDED");
    }

    private String statusOf(Long executionId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM workflow_execution WHERE id = ?", String.class, executionId);
    }

    private String statusOfNode(Long executionId, String nodeKey) {
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
                                .id("child").type("CODE")
                                .config(Map.of("operation", "identity"))
                                .dependencies(List.of("root"))
                                .build()))
                .build();

        var workflow = workflowService.create(name, null, "TEST", userId, document);
        var version = workflowService.listVersions(workflow.getId()).get(0);
        workflowService.publish(workflow.getId(), version.getId(), userId);
        WorkflowVersion published = workflowService.getPublishedVersion(workflow.getId());

        WorkflowExecution execution = executionService.start(published, document, userId, Map.of());
        return execution.getId();
    }
}
