package com.flowpilot.approval.service;

import com.flowpilot.agent.entity.AgentInvocation;
import com.flowpilot.agent.mapper.AgentInvocationMapper;
import com.flowpilot.agent.service.AgentInvocationService;
import com.flowpilot.approval.entity.ApprovalRecord;
import com.flowpilot.approval.mapper.ApprovalRecordMapper;
import com.flowpilot.common.exception.BizException;
import com.flowpilot.common.id.SnowflakeIdGenerator;
import com.flowpilot.common.result.ErrorCode;
import com.flowpilot.common.result.PageResult;
import com.flowpilot.common.util.Ids;
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

    /** The workflow fails when the approval expires. */
    public static final String TIMEOUT_ACTION_FAIL = "FAIL";
    /** The workflow continues as if the reviewer had agreed. */
    public static final String TIMEOUT_ACTION_AUTO_APPROVE = "AUTO_APPROVE";

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
     * The default deadline of an approval in minutes. Zero means that an approval waits forever.
     */
    @org.springframework.beans.factory.annotation.Value("${flowpilot.approval.timeout-minutes:1440}")
    private long defaultTimeoutMinutes;

    /**
     * The action that is taken when an approval expires.
     */
    @org.springframework.beans.factory.annotation.Value("${flowpilot.approval.timeout-action:FAIL}")
    private String defaultTimeoutAction;

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
                .expireAt(resolveExpireAt(requestData))
                .timeoutAction(resolveTimeoutAction(requestData))
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
                Map.of("approvalId", Ids.of(record.getId()),
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
        requireOwnership(approval, approverId);

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
                Map.of("approvalId", Ids.of(approvalId), "decision", status), approverId);

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
        output.put("approvalId", Ids.of(approval.getId()));

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
                    Map.of("reason", "APPROVAL_REJECTED", "approvalId", Ids.of(approval.getId())), null);
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

    /**
     * The approval, if the user is allowed to see it.
     *
     * <p>An approval belongs to the execution it belongs to, and an execution belongs to the user
     * that started it. Without the check, a user who knows the id of an approval could read the
     * request and the decision of somebody else.
     *
     * @param userId the caller, {@code null} skips the check
     * @param admin  whether the caller may see the approvals of everybody
     */
    public ApprovalRecord getAccessible(Long approvalId, Long userId, boolean admin) {
        ApprovalRecord approval = get(approvalId);
        if (!admin) {
            requireOwnership(approval, userId);
        }
        return approval;
    }

    public PageResult<ApprovalRecord> page(int page, int size, String status) {
        return page(page, size, status, null);
    }

    /**
     * The approvals of one user.
     *
     * <p>An approval belongs to the execution it belongs to, and an execution belongs to the user
     * that started it. A user therefore only sees the approvals of their own executions.
     */
    public PageResult<ApprovalRecord> page(int page, int size, String status, Long userId) {
        int limit = Math.max(1, size);
        int offset = Math.max(0, page - 1) * limit;
        long total = approvalMapper.countByOwner(userId, status);
        List<ApprovalRecord> records = approvalMapper.findByOwner(userId, status, limit, offset);
        return PageResult.of(total, page, size, records);
    }

    /**
     * Makes sure the reviewer is allowed to decide this approval.
     *
     * <p>Without the check, a user could decide an approval of an execution that belongs to
     * somebody else.
     */
    private void requireOwnership(ApprovalRecord approval, Long userId) {
        if (userId == null) {
            // Without a user there is nothing to compare the owner with. The caller that skips
            // the check has to be an administrator, and it does that through the admin flag of
            // getAccessible instead of an empty user id.
            throw new BizException(ErrorCode.AP_FORBIDDEN);
        }
        WorkflowExecution execution = executionMapper.selectById(approval.getWorkflowExecutionId());
        if (execution == null) {
            throw new BizException(ErrorCode.EX_NOT_FOUND);
        }
        if (!execution.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.AP_FORBIDDEN);
        }
    }

    /**
     * The node an approval belongs to.
     */
    public NodeExecution nodeOf(ApprovalRecord approval) {
        return nodeExecutionMapper.selectById(approval.getNodeExecutionId());
    }

    /**
     * Expires an approval whose deadline elapsed.
     *
     * <p>The deadline handling is part of the approval contract of the architecture design. An
     * approval that nobody answers must not block the workflow forever, so the approval is closed
     * and the workflow either continues or fails, depending on the configured action.
     *
     * @return true when this call expired the approval
     */
    @Transactional
    public boolean expire(ApprovalRecord approval) {
        if (!ApprovalRecord.STATUS_PENDING.equals(approval.getStatus())) {
            return false;
        }
        Map<String, Object> decisionData = new LinkedHashMap<>();
        decisionData.put("approved", false);
        decisionData.put("decision", ApprovalRecord.STATUS_EXPIRED);
        decisionData.put("expired", true);

        String comment = "The approval was not answered before its deadline";
        int updated = approvalMapper.expire(approval.getId(), JsonUtils.toJson(decisionData), comment);
        if (updated != 1) {
            // A reviewer decided it in the meantime.
            return false;
        }

        eventService.append(approval.getWorkflowExecutionId(), approval.getNodeExecutionId(),
                EventType.APPROVAL_EXPIRED,
                Map.of("approvalId", Ids.of(approval.getId()),
                        "timeoutAction", approval.getTimeoutAction() == null
                                ? TIMEOUT_ACTION_FAIL : approval.getTimeoutAction()), null);

        if (TIMEOUT_ACTION_AUTO_APPROVE.equals(approval.getTimeoutAction())) {
            resume(approval, "The approval expired and was approved automatically");
        } else {
            failExecution(approval, comment);
        }

        log.info("approval expired approvalId={} action={}", approval.getId(),
                approval.getTimeoutAction());
        return true;
    }

    /**
     * The deadline of an approval.
     *
     * <p>The node configuration may carry {@code timeoutMinutes}. Without it the global default
     * applies, and a default of zero means that the approval waits forever.
     */
    private LocalDateTime resolveExpireAt(Map<String, Object> requestData) {
        long minutes = defaultTimeoutMinutes;
        if (requestData != null) {
            Object configured = requestData.get("timeoutMinutes");
            if (configured != null) {
                try {
                    minutes = Long.parseLong(String.valueOf(configured));
                } catch (NumberFormatException ex) {
                    log.warn("the timeout of the approval is not a number: {}", configured);
                }
            }
        }
        if (minutes <= 0) {
            return null;
        }
        return LocalDateTime.now().plusMinutes(minutes);
    }

    /**
     * The action that is taken when the approval expires.
     */
    private String resolveTimeoutAction(Map<String, Object> requestData) {
        if (requestData != null) {
            Object configured = requestData.get("timeoutAction");
            if (configured != null) {
                String action = String.valueOf(configured).trim().toUpperCase(java.util.Locale.ROOT);
                if (TIMEOUT_ACTION_AUTO_APPROVE.equals(action)) {
                    return action;
                }
                if (TIMEOUT_ACTION_FAIL.equals(action)) {
                    return action;
                }
            }
        }
        return defaultTimeoutAction;
    }
}
