package com.flowpilot.approval.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flowpilot.agent.entity.AgentInvocation;
import com.flowpilot.agent.mapper.AgentInvocationMapper;
import com.flowpilot.agent.service.AgentInvocationService;
import com.flowpilot.approval.entity.ApprovalRecord;
import com.flowpilot.approval.mapper.ApprovalRecordMapper;
import com.flowpilot.common.exception.BizException;
import com.flowpilot.common.id.SnowflakeIdGenerator;
import com.flowpilot.common.result.ErrorCode;
import com.flowpilot.common.result.PageResult;
import com.flowpilot.common.util.JsonUtils;
import com.flowpilot.execution.entity.NodeExecution;
import com.flowpilot.execution.entity.WorkflowExecution;
import com.flowpilot.execution.mapper.NodeExecutionMapper;
import com.flowpilot.execution.mapper.WorkflowExecutionMapper;
import com.flowpilot.execution.model.EventType;
import com.flowpilot.execution.model.WorkflowStatus;
import com.flowpilot.execution.service.ExecutionEventService;
import com.flowpilot.execution.service.NodeResultService;
import com.flowpilot.execution.scheduler.WorkflowScheduler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Human approval handling.
 *
 * <p>The decision update is conditional on the approval still being pending, so a double click on
 * the approve button can only take effect once. The resume message is written in the same
 * transaction as the decision, so a decision can never be recorded without the workflow being
 * resumed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalService {

    public static final String DECISION_APPROVED = "APPROVED";
    public static final String DECISION_REJECTED = "REJECTED";

    private final ApprovalRecordMapper approvalMapper;
    private final AgentInvocationMapper invocationMapper;
    private final AgentInvocationService invocationService;
    private final NodeExecutionMapper nodeExecutionMapper;
    private final NodeResultService nodeResultService;
    private final WorkflowExecutionMapper executionMapper;
    private final ExecutionEventService eventService;
    private final WorkflowScheduler scheduler;
    private final SnowflakeIdGenerator idGenerator;

    /**
     * Creates a pending approval for a node that waits for a human.
     */
    @Transactional
    public ApprovalRecord request(Long workflowExecutionId, Long nodeExecutionId,
                                  String approvalType, String title, String description,
                                  Map<String, Object> requestData) {
        ApprovalRecord existing = approvalMapper.findPendingByExecution(workflowExecutionId);
        if (existing != null) {
            log.debug("an approval is already pending for execution {}", workflowExecutionId);
            return existing;
        }
        ApprovalRecord record = ApprovalRecord.builder()
                .id(idGenerator.nextId())
                .workflowExecutionId(workflowExecutionId)
                .nodeExecutionId(nodeExecutionId)
                .approvalType(approvalType == null ? "GENERIC" : approvalType)
                .status(ApprovalRecord.STATUS_PENDING)
                .title(title)
                .description(description)
                .requestData(JsonUtils.toJson(requestData == null ? Map.of() : requestData))
                .requestedAt(LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .build();
        approvalMapper.insert(record);

        WorkflowExecution execution = executionMapper.selectById(workflowExecutionId);
        if (execution != null && WorkflowStatus.canTransition(
                WorkflowStatus.valueOf(execution.getStatus()), WorkflowStatus.WAITING_HUMAN)) {
            executionMapper.compareAndSetStatus(workflowExecutionId,
                    execution.getStatus(),
                    execution.getVersion(),
                    WorkflowStatus.WAITING_HUMAN.name(),
                    execution.getStartedAt(),
                    execution.getFinishedAt(),
                    null, null);
        }

        eventService.append(workflowExecutionId, nodeExecutionId, EventType.APPROVAL_REQUESTED,
                Map.of("approvalId", record.getId(),
                        "approvalType", record.getApprovalType(),
                        "title", title == null ? "" : title), null);
        log.info("approval requested approvalId={} executionId={}", record.getId(), workflowExecutionId);
        return record;
    }

    /**
     * Records the decision of an approval and resumes the workflow.
     *
     * @param decision either {@code APPROVED} or {@code REJECTED}
     */
    @Transactional
    public ApprovalRecord decide(Long approvalId, String decision, String comment, Long approverId) {
        ApprovalRecord approval = approvalMapper.selectById(approvalId);
        if (approval == null) {
            throw new BizException(ErrorCode.AP_NOT_FOUND);
        }
        if (!ApprovalRecord.STATUS_PENDING.equals(approval.getStatus())) {
            throw new BizException(ErrorCode.AP_ILLEGAL_STATE,
                    "The approval was already decided with " + approval.getStatus());
        }

        String status = DECISION_APPROVED.equalsIgnoreCase(decision)
                ? ApprovalRecord.STATUS_APPROVED : ApprovalRecord.STATUS_REJECTED;

        Map<String, Object> decisionData = new LinkedHashMap<>();
        decisionData.put("approved", ApprovalRecord.STATUS_APPROVED.equals(status));
        decisionData.put("decision", status);
        decisionData.put("comment", comment);

        int updated = approvalMapper.decide(approvalId, status, JsonUtils.toJson(decisionData),
                comment, approverId);
        if (updated != 1) {
            // Another request decided it first. The recorded decision stays unchanged.
            throw new BizException(ErrorCode.AP_ILLEGAL_STATE,
                    "The approval was already decided");
        }

        eventService.append(approval.getWorkflowExecutionId(), approval.getNodeExecutionId(),
                EventType.APPROVAL_SUBMITTED,
                Map.of("approvalId", approvalId, "decision", status), approverId);

        if (ApprovalRecord.STATUS_APPROVED.equals(status)) {
            resume(approval, comment);
        } else {
            failExecution(approval, comment);
        }

        log.info("approval decided approvalId={} status={} approver={}", approvalId, status, approverId);
        return approvalMapper.selectById(approvalId);
    }

    /**
     * Continues the workflow after an approval was granted.
     *
     * <p>There are two kinds of waiting nodes. A human node of the workflow itself simply
     * finishes with the decision, and the scheduler continues with its successors. A node that
     * waits because an agent interrupted itself is resumed through the agent service, which
     * continues on the same thread.
     */
    private void resume(ApprovalRecord approval, String comment) {
        NodeExecution node = nodeExecutionMapper.selectById(approval.getNodeExecutionId());
        if (node == null) {
            return;
        }
        if ("HUMAN".equals(node.getNodeType())) {
            completeHumanNode(approval, node, comment);
            return;
        }
        resumeAgent(approval, comment);
    }

    /**
     * Completes a human node of the workflow and lets the scheduler continue.
     */
    private void completeHumanNode(ApprovalRecord approval, NodeExecution node, String comment) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("approved", true);
        output.put("comment", comment);
        output.put("approvalId", approval.getId());

        if (!nodeResultService.completeFromHumanWait(node.getId(), output)) {
            return;
        }

        // The workflow leaves the human waiting state before the successors are scheduled.
        resumeWorkflow(approval.getWorkflowExecutionId());

        // The scheduler learns about the completion through the event and continues the DAG.
        scheduler.onNodeCompleted(approval.getWorkflowExecutionId(), node.getNodeKey());
    }

    /**
     * Moves the workflow out of the human waiting state.
     */
    private void resumeWorkflow(Long workflowExecutionId) {
        WorkflowExecution execution = executionMapper.selectById(workflowExecutionId);
        if (execution == null || execution.getStatus() == null) {
            return;
        }
        WorkflowStatus current = WorkflowStatus.valueOf(execution.getStatus());
        if (current != WorkflowStatus.WAITING_HUMAN) {
            return;
        }
        executionMapper.compareAndSetStatus(workflowExecutionId,
                current.name(),
                execution.getVersion(),
                WorkflowStatus.RUNNING.name(),
                execution.getStartedAt(),
                execution.getFinishedAt(),
                null, null);
    }

    /**
     * Resumes the agent that paused for this approval.
     */
    private void resumeAgent(ApprovalRecord approval, String comment) {
        AgentInvocation invocation = findInvocationForNode(approval.getNodeExecutionId());
        if (invocation == null) {
            log.warn("no agent invocation found for node {}, the node is released directly",
                    approval.getNodeExecutionId());
            nodeExecutionMapper.releaseFromHumanWait(approval.getNodeExecutionId());
            return;
        }
        // The agent continues on the same thread, so the node returns to the running state. The
        // attempt number and the fencing token stay the same, which is what lets the agent commit
        // its result when it finishes.
        int resumed = nodeExecutionMapper.resumeRunningFromHumanWait(approval.getNodeExecutionId(),
                LocalDateTime.now().plusHours(2));
        if (resumed != 1) {
            log.warn("the agent node {} could not be resumed", approval.getNodeExecutionId());
            return;
        }
        // The resume request id is derived from the approval id, so a repeated decision can never
        // resume the agent twice.
        String resumeRequestId = "resume-" + approval.getId();
        Map<String, Object> resumeValue = new LinkedHashMap<>();
        resumeValue.put("approved", true);
        resumeValue.put("comment", comment);
        invocationService.resume(invocation.getId(), resumeRequestId, approval.getId(), resumeValue);

        // The workflow leaves the human waiting state while the agent continues.
        resumeWorkflow(approval.getWorkflowExecutionId());
    }

    /**
     * Fails the workflow when the approval was rejected.
     */
    private void failExecution(ApprovalRecord approval, String comment) {
        WorkflowExecution execution = executionMapper.selectById(approval.getWorkflowExecutionId());
        if (execution == null || execution.getStatus() == null) {
            return;
        }
        WorkflowStatus current = WorkflowStatus.valueOf(execution.getStatus());
        if (current.isTerminal()) {
            return;
        }
        if (WorkflowStatus.canTransition(current, WorkflowStatus.FAILED)) {
            executionMapper.compareAndSetStatus(execution.getId(),
                    current.name(),
                    execution.getVersion(),
                    WorkflowStatus.FAILED.name(),
                    execution.getStartedAt(),
                    LocalDateTime.now(),
                    "APPROVAL_REJECTED",
                    comment == null ? "The reviewer rejected the result" : comment);
            eventService.append(execution.getId(), approval.getNodeExecutionId(),
                    EventType.WORKFLOW_FAILED,
                    Map.of("reason", "APPROVAL_REJECTED", "approvalId", approval.getId()), null);
        }
        // The node that waited for the decision is failed as well, so the console shows why the
        // workflow stopped.
        nodeResultService.failFromHumanWait(approval.getNodeExecutionId(),
                "APPROVAL_REJECTED",
                comment == null ? "The reviewer rejected the result" : comment);
    }

    private AgentInvocation findInvocationForNode(Long nodeExecutionId) {
        List<AgentInvocation> invocations = invocationMapper.findByNodeExecutionId(nodeExecutionId);
        if (invocations.isEmpty()) {
            return null;
        }
        return invocations.get(invocations.size() - 1);
    }

    public ApprovalRecord get(Long approvalId) {
        ApprovalRecord approval = approvalMapper.selectById(approvalId);
        if (approval == null) {
            throw new BizException(ErrorCode.AP_NOT_FOUND);
        }
        return approval;
    }

    public PageResult<ApprovalRecord> page(int page, int size, String status) {
        LambdaQueryWrapper<ApprovalRecord> query = new LambdaQueryWrapper<ApprovalRecord>()
                .eq(status != null && !status.isBlank(), ApprovalRecord::getStatus, status)
                .orderByDesc(ApprovalRecord::getCreatedAt);
        Page<ApprovalRecord> result = approvalMapper.selectPage(new Page<>(page, size), query);
        return PageResult.of(result.getTotal(), page, size, result.getRecords());
    }

    /**
     * The node an approval belongs to.
     */
    public NodeExecution nodeOf(ApprovalRecord approval) {
        return nodeExecutionMapper.selectById(approval.getNodeExecutionId());
    }
}
