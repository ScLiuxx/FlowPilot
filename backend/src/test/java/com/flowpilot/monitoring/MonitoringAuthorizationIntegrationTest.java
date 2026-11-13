package com.flowpilot.monitoring;

import com.flowpilot.execution.entity.WorkflowExecution;
import com.flowpilot.execution.mapper.WorkflowExecutionMapper;
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
 * Integration tests of the statistics of the dashboard.
 *
 * <p>The numbers belong to the executions of the user: a normal user must not see the executions
 * of somebody else.
 */
@SpringBootTest
@ActiveProfiles("test")
class MonitoringAuthorizationIntegrationTest {

    @Autowired
    private WorkflowService workflowService;
    @Autowired
    private WorkflowExecutionService executionService;
    @Autowired
    private WorkflowScheduler scheduler;
    @Autowired
    private WorkflowExecutionMapper executionMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("the number of the executions can be counted for one owner")
    void theExecutionsAreCountedForOneOwner() {
        Long owner = 9701L;
        Long other = 9702L;
        Long executionId = startWorkflow(owner, "monitoring demo");
        startWorkflow(other, "monitoring demo of another user");

        long mine = executionMapper.countByOwner(owner, null);
        long theirs = executionMapper.countByOwner(other, null);
        long all = executionMapper.countByOwner(null, null);

        assertThat(mine).isGreaterThanOrEqualTo(1);
        assertThat(theirs).isGreaterThanOrEqualTo(1);
        assertThat(all).isGreaterThanOrEqualTo(mine + theirs);
        assertThat(all).isGreaterThan(mine);

        jdbcTemplate.update("DELETE FROM workflow_execution WHERE id = ?", executionId);
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
        var version = workflowService.listVersions(workflow.getId()).get(0);
        workflowService.publish(workflow.getId(), version.getId(), userId);
        WorkflowVersion published = workflowService.getPublishedVersion(workflow.getId());

        WorkflowExecution execution = executionService.start(published, document, userId, Map.of());
        scheduler.onWorkflowStarted(execution.getId());
        return execution.getId();
    }
}
