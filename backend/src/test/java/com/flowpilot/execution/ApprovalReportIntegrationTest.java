package com.flowpilot.execution;

import com.flowpilot.agent.entity.AgentInvocation;
import com.flowpilot.agent.mapper.AgentInvocationMapper;
import com.flowpilot.agent.service.AgentEventHandler;
import com.flowpilot.approval.entity.ApprovalRecord;
import com.flowpilot.approval.mapper.ApprovalRecordMapper;
import com.flowpilot.common.util.JsonUtils;
import com.flowpilot.execution.entity.NodeExecution;
import com.flowpilot.execution.entity.WorkflowExecution;
import com.flowpilot.execution.mapper.NodeExecutionMapper;
import com.flowpilot.execution.scheduler.WorkflowScheduler;
import com.flowpilot.execution.service.WorkflowExecutionService;
import com.flowpilot.execution.worker.NodeWorker;
import com.flowpilot.messaging.protocol.payloads.AgentCompletedPayload;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests of the content of an approval request.
 *
 * <p>The approval request is the only thing the reviewer sees. It used to carry the title of the
 * report, so the reviewer had to approve a decision they could not read. The report of the
 * analysis therefore travels with the request.
 */
@SpringBootTest
@ActiveProfiles("test")
class ApprovalReportIntegrationTest {

    /** The report the agent produced. */
    private static final Map<String, Object> REPORT = Map.of(
            "title", "example/project 技术尽调报告",
            "summary", "结论符合要求。",
            "riskLevel", "MEDIUM",
            "sections", List.of(
                    Map.of("key", "architecture", "title", "Architecture Analysis",
                            "content", "The repository exposes a single public build."),
                    Map.of("key", "risk", "title", "Risk Analysis",
                            "content", "No critical blocker was detected.")));

    @Autowired
    private WorkflowService workflowService;
    @Autowired
    private WorkflowExecutionService executionService;
    @Autowired
    private WorkflowScheduler scheduler;
    @Autowired
    private NodeExecutionMapper nodeExecutionMapper;
    @Autowired
    private AgentInvocationMapper invocationMapper;
    @Autowired
    private AgentEventHandler agentEventHandler;
    @Autowired
    private ApprovalRecordMapper approvalMapper;
    @Autowired
    private NodeWorker nodeWorker;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("the approval of a human node carries the report of the analysis")
    void theApprovalCarriesTheReport() {
        Long userId = 9301L;
        Long executionId = startWorkflow(userId, "approval report demo");

        // The agent analysed the repository and reported its result.
        NodeExecution agentNode = nodeOf(executionId, "analyze");
        AgentInvocation invocation = invocationMapper.findByNodeExecutionId(agentNode.getId()).get(0);
        transactionTemplate.executeWithoutResult(status -> agentEventHandler.onCompleted(
                AgentCompletedPayload.builder()
                        .invocationId(invocation.getId())
                        .threadId(invocation.getThreadId())
                        .checkpointId("checkpoint-report")
                        .result(Map.of("report", REPORT))
                        .build()));

        // The scheduler continues with the human node.
        scheduler.onNodeCompleted(executionId, "analyze");
        NodeExecution humanNode = nodeOf(executionId, "human_review");
        assertThat(humanNode.getStatus()).isEqualTo("READY");

        // The worker runs the human node, which pauses the workflow for a decision.
        nodeWorker.execute(
                NodeExecutePayload.builder()
                        .nodeKey("human_review")
                        .nodeType("HUMAN")
                        .config(Map.of("approvalType", "REPORT_REVIEW"))
                        .input(Map.of())
                        .build(),
                executionId, humanNode.getId(), 1, null, null);

        // The reviewer sees the report, not only its title.
        ApprovalRecord approval = approvalMapper.findPendingByExecution(executionId);
        assertThat(approval).isNotNull();
        Map<String, Object> requestData = JsonUtils.toMap(approval.getRequestData());
        assertThat(requestData).containsKey("report");

        Map<String, Object> report = JsonUtils.toMap(requestData.get("report"));
        assertThat(report).containsEntry("title", "example/project 技术尽调报告");
        assertThat(report).containsEntry("summary", "结论符合要求。");
        assertThat(report).containsEntry("riskLevel", "MEDIUM");
        assertThat((List<?>) report.get("sections"))
                .as("every section of the report travels with the request")
                .hasSize(2);
    }

    private Long startWorkflow(Long userId, String name) {
        WorkflowDefinitionDocument document = WorkflowDefinitionDocument.builder()
                .name("test")
                .nodes(List.of(
                        WorkflowNodeDefinition.builder()
                                .id("analyze").type("AGENT")
                                .config(Map.of("agentType", "REPOSITORY_ANALYST"))
                                .dependencies(List.of())
                                .build(),
                        WorkflowNodeDefinition.builder()
                                .id("human_review").type("HUMAN")
                                .config(Map.of("approvalType", "REPORT_REVIEW"))
                                .dependencies(List.of("analyze"))
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

    private NodeExecution nodeOf(Long executionId, String nodeKey) {
        return executionService.nodes(executionId).stream()
                .filter(node -> nodeKey.equals(node.getNodeKey()))
                .findFirst()
                .orElseThrow();
    }
}
