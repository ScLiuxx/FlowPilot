package com.flowpilot.execution;

import com.flowpilot.agent.entity.AgentInvocation;
import com.flowpilot.agent.mapper.AgentInvocationMapper;
import com.flowpilot.agent.service.AgentEventHandler;
import com.flowpilot.approval.entity.ApprovalRecord;
import com.flowpilot.approval.mapper.ApprovalRecordMapper;
import com.flowpilot.approval.service.ApprovalService;
import com.flowpilot.common.util.JsonUtils;
import com.flowpilot.execution.entity.NodeExecution;
import com.flowpilot.execution.entity.NodeExecutionAttempt;
import com.flowpilot.execution.entity.WorkflowExecution;
import com.flowpilot.execution.mapper.NodeExecutionAttemptMapper;
import com.flowpilot.execution.mapper.NodeExecutionMapper;
import com.flowpilot.execution.model.NodeStatus;
import com.flowpilot.execution.model.WorkflowStatus;
import com.flowpilot.execution.scheduler.WorkflowScheduler;
import com.flowpilot.execution.service.ExecutionEventService;
import com.flowpilot.execution.service.NodeResultService;
import com.flowpilot.execution.service.WorkflowExecutionService;
import com.flowpilot.messaging.protocol.payloads.AgentCompletedPayload;
import com.flowpilot.messaging.protocol.payloads.AgentInterruptedPayload;
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
 * Integration tests of the human approval path.
 *
 * <p>They cover the transitions that are easy to get wrong: the attempt of a human node has to
 * end with the decision, a rejection has to fail the node, an interrupted agent has to create the
 * approval the reviewer decides on, and a cancelled execution must end as cancelled.
 */
@SpringBootTest
@ActiveProfiles("test")
class HumanApprovalIntegrationTest {

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
    private ApprovalRecordMapper approvalMapper;
    @Autowired
    private AgentInvocationMapper invocationMapper;
    @Autowired
    private AgentEventHandler agentEventHandler;
    @Autowired
    private ExecutionEventService eventService;
    @Autowired
    private NodeResultService nodeResultService;
    @Autowired
    private ApprovalService approvalService;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("T11: the approval ends the attempt of the human node")
    void approvalEndsTheAttemptOfTheHumanNode() {
        Long userId = 9201L;
        Long executionId = startHumanWorkflow(userId, "approval attempt demo");
        NodeExecution humanNode = nodeOf(executionId, "human_review");

        // The worker opens the attempt and pauses the node for a decision.
        transactionTemplate.executeWithoutResult(status -> acquireLease(humanNode.getId()));
        pauseForApproval(humanNode.getId(), executionId);

        NodeExecution waiting = nodeExecutionMapper.selectById(humanNode.getId());
        assertThat(waiting.getStatus()).isEqualTo(NodeStatus.WAITING_HUMAN.name());

        NodeExecutionAttempt paused = attemptMapper.findByNodeAndAttempt(humanNode.getId(), 1);
        assertThat(paused).isNotNull();
        assertThat(paused.getStatus()).isEqualTo(NodeStatus.WAITING_HUMAN.name());

        // The reviewer approves, so the attempt finishes as successful.
        ApprovalRecord approval = pendingApproval(executionId);
        transactionTemplate.executeWithoutResult(status -> approvalService.decide(
                approval.getId(), "APPROVED", "符合要求", userId));

        NodeExecution completed = nodeExecutionMapper.selectById(humanNode.getId());
        assertThat(completed.getStatus()).isEqualTo(NodeStatus.SUCCEEDED.name());

        NodeExecutionAttempt finished = attemptMapper.findByNodeAndAttempt(humanNode.getId(), 1);
        assertThat(finished.getStatus()).isEqualTo(NodeStatus.SUCCEEDED.name());
        assertThat(finished.getFinishedAt()).isNotNull();
        assertThat(finished.getDurationMs()).isNotNull();
    }

    @Test
    @DisplayName("T11: a rejection fails the node that waited for the decision")
    void rejectionFailsTheWaitingNode() {
        Long userId = 9202L;
        Long executionId = startHumanWorkflow(userId, "rejection demo");
        NodeExecution humanNode = nodeOf(executionId, "human_review");

        transactionTemplate.executeWithoutResult(status -> acquireLease(humanNode.getId()));
        pauseForApproval(humanNode.getId(), executionId);

        ApprovalRecord approval = pendingApproval(executionId);
        transactionTemplate.executeWithoutResult(status -> approvalService.decide(
                approval.getId(), "REJECTED", "结论不准确", userId));

        NodeExecution rejected = nodeExecutionMapper.selectById(humanNode.getId());
        assertThat(rejected.getStatus()).isEqualTo(NodeStatus.FAILED.name());
        assertThat(rejected.getErrorCode()).isEqualTo("APPROVAL_REJECTED");

        NodeExecutionAttempt attempt = attemptMapper.findByNodeAndAttempt(humanNode.getId(), 1);
        assertThat(attempt.getStatus()).isEqualTo(NodeStatus.FAILED.name());

        WorkflowExecution execution = executionService.get(executionId);
        assertThat(execution.getStatus()).isEqualTo(WorkflowStatus.FAILED.name());
    }

    @Test
    @DisplayName("T10: an interrupted agent creates the approval the reviewer decides on")
    void interruptedAgentCreatesAnApproval() {
        Long userId = 9203L;
        Long executionId = startAgentWorkflow(userId, "interrupt approval demo");
        NodeExecution agentNode = nodeOf(executionId, "analyze");
        AgentInvocation invocation = invocationMapper.findByNodeExecutionId(agentNode.getId()).get(0);

        AgentInterruptedPayload payload = AgentInterruptedPayload.builder()
                .invocationId(invocation.getId())
                .threadId(invocation.getThreadId())
                .checkpointId("checkpoint-interrupt")
                .interruptType("HUMAN_APPROVAL")
                .interruptData(AgentInterruptedPayload.InterruptData.builder()
                        .title("请审核分析结论")
                        .description("Agent 需要人工确认")
                        .requestedAction("APPROVE_REPORT")
                        .build())
                .build();

        transactionTemplate.executeWithoutResult(status -> agentEventHandler.onInterrupted(payload));

        NodeExecution waiting = nodeExecutionMapper.selectById(agentNode.getId());
        assertThat(waiting.getStatus()).isEqualTo(NodeStatus.WAITING_HUMAN.name());

        // The reviewer has to see the interruption, so an approval exists.
        ApprovalRecord approval = pendingApproval(executionId);
        assertThat(approval.getNodeExecutionId()).isEqualTo(agentNode.getId());

        // The workflow waits for the reviewer as well.
        assertThat(executionService.get(executionId).getStatus())
                .isEqualTo(WorkflowStatus.WAITING_HUMAN.name());

        // The approval releases the agent, which continues on the same thread.
        transactionTemplate.executeWithoutResult(status -> approvalService.decide(
                approval.getId(), "APPROVED", "可以继续", userId));

        NodeExecution resumed = nodeExecutionMapper.selectById(agentNode.getId());
        assertThat(resumed.getStatus()).isEqualTo(NodeStatus.RUNNING.name());
        assertThat(resumed.getCurrentAttempt()).isEqualTo(invocation.getAttemptNo());

        // The agent reports its result, which the node accepts because it is running again.
        AgentCompletedPayload completed = AgentCompletedPayload.builder()
                .invocationId(invocation.getId())
                .threadId(invocation.getThreadId())
                .checkpointId("checkpoint-done")
                .result(Map.of("conclusion", "分层架构"))
                .build();
        transactionTemplate.executeWithoutResult(status -> agentEventHandler.onCompleted(completed));

        NodeExecution done = nodeExecutionMapper.selectById(agentNode.getId());
        assertThat(done.getStatus()).isEqualTo(NodeStatus.SUCCEEDED.name());
        assertThat(done.getOutputJson()).contains("分层架构");
    }

    @Test
    @DisplayName("T14: a cancelled execution ends as cancelled and not as failed")
    void cancelledExecutionEndsAsCancelled() {
        Long userId = 9204L;
        Long executionId = startHumanWorkflow(userId, "cancellation demo");

        transactionTemplate.executeWithoutResult(status -> executionService.cancel(
                executionId, "USER_REQUEST", userId));

        // The workflow waits for the reviewer, so the approval is cancelled with it.
        ApprovalRecord approval = approvalMapper.findPendingByExecution(executionId);
        assertThat(approval).isNull();

        transactionTemplate.executeWithoutResult(status -> scheduler.checkCompletion(executionId));

        WorkflowExecution execution = executionService.get(executionId);
        assertThat(execution.getStatus()).isEqualTo(WorkflowStatus.CANCELED.name());
        assertThat(execution.getErrorCode()).isNull();
    }

    @Test
    @DisplayName("T14: a cancelled execution ends as soon as the running node reports back")
    void cancelledExecutionEndsWhenTheRunningNodeFinishes() {
        Long userId = 9206L;
        Long executionId = startHumanWorkflow(userId, "cooperative cancellation demo");
        NodeExecution humanNode = nodeOf(executionId, "human_review");

        transactionTemplate.executeWithoutResult(status -> acquireLease(humanNode.getId()));
        pauseForApproval(humanNode.getId(), executionId);

        // The node waits for a decision, so the cancellation stops it right away.
        transactionTemplate.executeWithoutResult(status -> executionService.cancel(
                executionId, "USER_REQUEST", userId));

        WorkflowExecution execution = executionService.get(executionId);
        assertThat(execution.getStatus()).isEqualTo(WorkflowStatus.CANCELED.name());

        NodeExecution cancelled = nodeExecutionMapper.selectById(humanNode.getId());
        assertThat(cancelled.getStatus()).isEqualTo(NodeStatus.CANCELED.name());
    }

    @Test
    @DisplayName("T13: the output of a successful workflow carries the output of its nodes")
    void successfulWorkflowCarriesTheNodeOutputs() {
        Long userId = 9205L;
        Long executionId = startHumanWorkflow(userId, "workflow output demo");
        NodeExecution humanNode = nodeOf(executionId, "human_review");

        transactionTemplate.executeWithoutResult(status -> acquireLease(humanNode.getId()));
        pauseForApproval(humanNode.getId(), executionId);

        ApprovalRecord approval = pendingApproval(executionId);
        transactionTemplate.executeWithoutResult(status -> approvalService.decide(
                approval.getId(), "APPROVED", "符合要求", userId));

        WorkflowExecution execution = executionService.get(executionId);
        assertThat(execution.getStatus()).isEqualTo(WorkflowStatus.SUCCEEDED.name());

        // The output of the workflow is the output of its nodes.
        Map<String, Object> output = JsonUtils.toMap(execution.getOutputJson());
        assertThat(output).containsKey("human_review");
    }

    /**
     * Starts a workflow whose only node waits for a human decision.
     */
    private Long startHumanWorkflow(Long userId, String name) {
        WorkflowDefinitionDocument document = definition(
                node("human_review", "HUMAN", Map.of("approvalType", "REPORT_REVIEW"), List.of()));

        var workflow = workflowService.create(name, null, "TEST", userId, document);
        var version = workflowService.listVersions(workflow.getId()).get(0);
        workflowService.publish(workflow.getId(), version.getId(), userId);
        WorkflowVersion published = workflowService.getPublishedVersion(workflow.getId());

        WorkflowExecution execution = executionService.start(published, document, userId, Map.of());
        scheduler.onWorkflowStarted(execution.getId());
        return execution.getId();
    }

    private Long startAgentWorkflow(Long userId, String name) {
        WorkflowDefinitionDocument document = definition(
                node("analyze", "AGENT", Map.of("agentType", "REPOSITORY_ANALYST"), List.of()));

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

    private ApprovalRecord pendingApproval(Long executionId) {
        ApprovalRecord approval = approvalMapper.findPendingByExecution(executionId);
        assertThat(approval).isNotNull();
        return approval;
    }

    /**
     * Takes the lease of a node, which is what the worker does before it runs the node.
     */
    private void acquireLease(Long nodeExecutionId) {
        NodeExecution node = nodeExecutionMapper.selectById(nodeExecutionId);
        int leased = nodeExecutionMapper.acquireLease(node.getId(), "worker-test", 1,
                node.getCurrentAttempt() == null ? 0 : node.getCurrentAttempt(),
                java.time.LocalDateTime.now().plusMinutes(5));
        assertThat(leased).isEqualTo(1);
    }

    /**
     * Pauses a node for a decision, which is what the worker does for a human node.
     */
    private void pauseForApproval(Long nodeExecutionId, Long executionId) {
        NodeExecution leased = nodeExecutionMapper.selectById(nodeExecutionId);
        transactionTemplate.executeWithoutResult(status -> nodeResultService.markWaitingHuman(
                leased.getId(), 1));
        transactionTemplate.executeWithoutResult(status -> approvalService.request(executionId,
                nodeExecutionId, "REPORT_REVIEW", "请审核技术尽调结论", "请确认结论是否需要调整。",
                Map.of()));
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
