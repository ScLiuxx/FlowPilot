package com.flowpilot.execution;

import com.flowpilot.execution.entity.NodeExecution;
import com.flowpilot.execution.entity.WorkflowExecution;
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
 * Integration tests of the nodes with a large output.
 *
 * <p>The output of a node carries the raw response of an external system and can be larger than the
 * sort buffer of the database. A query that sorts such a row fails with "out of sort memory", so
 * the identifiers are read first and the rows afterwards.
 */
@SpringBootTest
@ActiveProfiles("test")
class WideNodeOutputIntegrationTest {

    /** The size of the default sort buffer of the database. */
    private static final int SORT_BUFFER_SIZE = 262144;

    @Autowired
    private WorkflowService workflowService;
    @Autowired
    private WorkflowExecutionService executionService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("the nodes of an execution are read even when the output is large")
    void theNodesAreReadWithALargeOutput() {
        Long executionId = startWorkflow(9604L, "wide output demo");
        jdbcTemplate.update("UPDATE node_execution SET output_json = ? WHERE workflow_execution_id = ?",
                "{\"body\":\"" + "x".repeat(SORT_BUFFER_SIZE * 2) + "\"}", executionId);

        List<NodeExecution> nodes = executionService.nodesWithOutput(executionId);

        assertThat(nodes).hasSize(1);
        assertThat(nodes.get(0).getOutputJson()).hasSizeGreaterThan(SORT_BUFFER_SIZE);
    }

    private Long startWorkflow(Long userId, String name) {
        WorkflowDefinitionDocument document = WorkflowDefinitionDocument.builder()
                .name("test")
                .nodes(List.of(WorkflowNodeDefinition.builder()
                        .id("only").type("CODE")
                        .config(Map.of("operation", "identity"))
                        .dependencies(List.of())
                        .build()))
                .build();

        var workflow = workflowService.create(name, null, "TEST", userId, document);
        var version = workflowService.listVersionsInternal(workflow.getId()).get(0);
        workflowService.publish(workflow.getId(), version.getId(), userId);
        WorkflowVersion published = workflowService.getPublishedVersion(workflow.getId());

        WorkflowExecution execution = executionService.start(published, document, userId, Map.of());
        return execution.getId();
    }
}
