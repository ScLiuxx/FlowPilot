package com.flowpilot.execution;

import com.flowpilot.approval.entity.ApprovalRecord;
import com.flowpilot.approval.mapper.ApprovalRecordMapper;
import com.flowpilot.approval.service.ApprovalService;
import com.flowpilot.approval.service.ApprovalTimeoutScheduler;
import com.flowpilot.execution.entity.NodeExecution;
import com.flowpilot.execution.entity.WorkflowExecution;
import com.flowpilot.execution.mapper.NodeExecutionMapper;
import com.flowpilot.execution.model.NodeStatus;
import com.flowpilot.execution.model.WorkflowStatus;
import com.flowpilot.execution.scheduler.WorkflowScheduler;
import com.flowpilot.execution.service.ExecutionEventService;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests of the approval timeout.
 *
 * <p>The approval contract of the architecture design asks for a timeout handling. A workflow
 * that waits for a reviewer nobody answers must not block the resources of the platform
 * forever, so the approval is closed and the workflow either fails or continues.
 */
@SpringBootTest
@ActiveProfiles("test")
class ApprovalTimeoutIntegrationTest {

    @Autowired
    private WorkflowService workflowService;
    @Autowired
    private WorkflowExecutionService executionService;
    @Autowired
    private WorkflowScheduler scheduler;
    @Autowired
    private NodeExecutionMapper nodeExecutionMapper;
    @Autowired
    private ApprovalRecordMapper approvalMapper;
    @Autowired
    private ApprovalService approvalService;
    @Autowired
    private ApprovalTimeoutScheduler timeoutScheduler;
    @Autowired
    private ExecutionEventService eventService;
    @Autowired
    private NodeResultService nodeResultService;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("an approval gets a deadline")
    void approvalHasADeadline() {
        Long userId = 9301L;
        Long executionId = startHumanWorkflow(userId, "approval deadline demo");
        NodeExecution humanNode = nodeOf(executionId, "human_review");

        transactionTemplate.executeWithoutResult(status -> acquireLease(humanNode.getId()));
        pauseForApproval(humanNode.getId(), executionId);

        ApprovalRecord approval = approvalMapper.findPendingByExecution(executionId);
        assertThat(approval).isNotNull();
        assertThat(approval.getExpireAt()).isNotNull();
        assertThat(approval.getExpireAt()).isAfter(LocalDateTime.now());
        assertThat(approval.getTimeoutAction()).isEqualTo(ApprovalService.TIMEOUT_ACTION_FAIL);
    }

    @Test
    @DisplayName("an expired approval fails the node and the workflow")
    void expiredApprovalFailsTheWorkflow() {
        Long userId = 9302L;
        Long executionId = startHumanWorkflow(userId, "approval expiry demo");
        NodeExecution humanNode = nodeOf(executionId, "human_review");

        transactionTemplate.executeWithoutResult(status -> acquireLease(humanNode.getId()));
        pauseForApproval(humanNode.getId(), executionId);

        ApprovalRecord approval = approvalMapper.findPendingByExecution(executionId);
        expireNow(approval.getId());

        // The scanner finds the approval and closes it.
        timeoutScheduler.closeExpiredApprovals();

        ApprovalRecord expired = approvalMapper.selectById(approval.getId());
        assertThat(expired.getStatus()).isEqualTo(ApprovalRecord.STATUS_EXPIRED);

        NodeExecution failed = nodeExecutionMapper.selectById(humanNode.getId());
        assertThat(failed.getStatus()).isEqualTo(NodeStatus.FAILED.name());
        assertThat(failed.getErrorCode()).isEqualTo("APPROVAL_REJECTED");

        WorkflowExecution execution = executionService.get(executionId);
        assertThat(execution.getStatus()).isEqualTo(WorkflowStatus.FAILED.name());
    }

    @Test
    @DisplayName("an expired approval with the auto approve action continues the workflow")
    void expiredApprovalCanAutoApprove() {
        Long userId = 9303L;
        Long executionId = startHumanWorkflow(userId, "approval auto approve demo");
        NodeExecution humanNode = nodeOf(executionId, "human_review");

        transactionTemplate.executeWithoutResult(status -> acquireLease(humanNode.getId()));
        pauseForApproval(humanNode.getId(), executionId);

        ApprovalRecord approval = approvalMapper.findPendingByExecution(executionId);
        transactionTemplate.executeWithoutResult(status -> approvalMapper.updateById(
                ApprovalRecord.builder()
                        .id(approval.getId())
                        .timeoutAction(ApprovalService.TIMEOUT_ACTION_AUTO_APPROVE)
                        .expireAt(LocalDateTime.now().minusMinutes(1))
                        .build()));
        expireNow(approval.getId());

        timeoutScheduler.closeExpiredApprovals();

        NodeExecution completed = nodeExecutionMapper.selectById(humanNode.getId());
        assertThat(completed.getStatus()).isEqualTo(NodeStatus.SUCCEEDED.name());

        WorkflowExecution execution = executionService.get(executionId);
        assertThat(execution.getStatus()).isEqualTo(WorkflowStatus.SUCCEEDED.name());
    }

    @Test
    @DisplayName("an approval that was decided is not expired")
    void decidedApprovalIsNotExpired() {
        Long userId = 9304L;
        Long executionId = startHumanWorkflow(userId, "decided approval demo");
        NodeExecution humanNode = nodeOf(executionId, "human_review");

        transactionTemplate.executeWithoutResult(status -> acquireLease(humanNode.getId()));
        pauseForApproval(humanNode.getId(), executionId);

        ApprovalRecord approval = approvalMapper.findPendingByExecution(executionId);
        transactionTemplate.executeWithoutResult(status -> approvalService.decide(
                approval.getId(), "APPROVED", "符合要求", userId));

        expireNow(approval.getId());
        timeoutScheduler.closeExpiredApprovals();

        ApprovalRecord decided = approvalMapper.selectById(approval.getId());
        assertThat(decided.getStatus()).isEqualTo(ApprovalRecord.STATUS_APPROVED);
    }

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

    private NodeExecution nodeOf(Long executionId, String nodeKey) {
        return executionService.nodes(executionId).stream()
                .filter(node -> nodeKey.equals(node.getNodeKey()))
                .findFirst()
                .orElseThrow();
    }

    private void acquireLease(Long nodeExecutionId) {
        NodeExecution node = nodeExecutionMapper.selectById(nodeExecutionId);
        int leased = nodeExecutionMapper.acquireLease(node.getId(), "worker-test", 1,
                node.getCurrentAttempt() == null ? 0 : node.getCurrentAttempt(),
                LocalDateTime.now().plusMinutes(5));
        assertThat(leased).isEqualTo(1);
    }

    private void pauseForApproval(Long nodeExecutionId, Long executionId) {
        NodeExecution leased = nodeExecutionMapper.selectById(nodeExecutionId);
        transactionTemplate.executeWithoutResult(status -> nodeResultService.markWaitingHuman(
                leased.getId(), 1));
        transactionTemplate.executeWithoutResult(status -> approvalService.request(executionId,
                nodeExecutionId, "REPORT_REVIEW", "请审核技术尽调结论", "请确认结论是否需要调整。",
                Map.of()));
    }

    /**
     * Moves the deadline of an approval into the past.
     */
    private void expireNow(Long approvalId) {
        transactionTemplate.executeWithoutResult(status -> {
            ApprovalRecord approval = approvalMapper.selectById(approvalId);
            approval.setExpireAt(LocalDateTime.now().minusMinutes(1));
            approvalMapper.updateById(approval);
        });
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
