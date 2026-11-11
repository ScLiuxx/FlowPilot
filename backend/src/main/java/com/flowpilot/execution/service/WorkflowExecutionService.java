package com.flowpilot.execution.service;

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
import com.flowpilot.common.trace.TraceContext;
import com.flowpilot.common.util.Ids;
import com.flowpilot.common.util.JsonUtils;
import com.flowpilot.execution.entity.NodeExecution;
import com.flowpilot.execution.entity.WorkflowExecution;
import com.flowpilot.execution.mapper.NodeExecutionMapper;
import com.flowpilot.execution.mapper.WorkflowExecutionMapper;
import com.flowpilot.execution.model.EventType;
import com.flowpilot.execution.model.NodeStatus;
import com.flowpilot.execution.model.NodeType;
import com.flowpilot.execution.model.RetryPolicy;
import com.flowpilot.execution.model.WorkflowStatus;
import com.flowpilot.messaging.outbox.OutboxService;
import com.flowpilot.messaging.protocol.MessageEnvelope;
import com.flowpilot.messaging.protocol.MessageType;
import com.flowpilot.messaging.protocol.payloads.WorkflowStartedPayload;
import com.flowpilot.workflow.definition.WorkflowDefinitionDocument;
import com.flowpilot.workflow.definition.WorkflowNodeDefinition;
import com.flowpilot.workflow.entity.WorkflowVersion;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Owns the lifecycle of a workflow execution.
 *
 * <p>Creating an execution is a single MySQL transaction that writes the execution row, all node
 * rows, the first event and the outbox message. Either the task exists completely or it does not
 * exist at all, which is what makes a restart safe.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowExecutionService {

    private static final DateTimeFormatter EXECUTION_NO_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd");

    private final WorkflowExecutionMapper executionMapper;
    private final NodeExecutionMapper nodeExecutionMapper;
    private final ApprovalRecordMapper approvalMapper;
    private final AgentInvocationMapper invocationMapper;
    private final AgentInvocationService invocationService;
    private final ExecutionEventService eventService;
    private final OutboxService outboxService;
    private final SnowflakeIdGenerator idGenerator;

    /**
     * Starts a new execution of a published workflow version.
     *
     * <p>When the caller provides an idempotency key, a repeated request returns the execution
     * that was started with the same key instead of starting a second one.
     */
    @Transactional
    public WorkflowExecution start(WorkflowVersion version,
                                   WorkflowDefinitionDocument document,
                                   Long userId,
                                   Map<String, Object> input) {
        return start(version, document, userId, input, null);
    }

    /**
     * Starts a new execution, or returns the existing one for a repeated idempotency key.
     */
    @Transactional
    public WorkflowExecution start(WorkflowVersion version,
                                   WorkflowDefinitionDocument document,
                                   Long userId,
                                   Map<String, Object> input,
                                   String idempotencyKey) {
        String key = idempotencyKey == null || idempotencyKey.isBlank()
                ? null : idempotencyKey.trim();
        if (key != null) {
            WorkflowExecution existing = executionMapper.findByIdempotencyKey(userId, key);
            if (existing != null) {
                log.info("the idempotency key {} was used before, returning execution {}",
                        key, existing.getId());
                return existing;
            }
        }

        Long executionId = idGenerator.nextId();
        // The whole execution belongs to one trace. The id is stored on the execution row, so
        // every message and every span of this execution can be collected under it.
        String traceId = TraceContext.currentTraceId();
        WorkflowExecution execution = WorkflowExecution.builder()
                .id(executionId)
                .workflowId(version.getWorkflowId())
                .workflowVersionId(version.getId())
                .userId(userId)
                .executionNo(buildExecutionNo(executionId))
                // The execution waits in the queue until the scheduler picks it up.
                .status(WorkflowStatus.QUEUED.name())
                .idempotencyKey(key)
                .traceId(traceId)
                .inputJson(JsonUtils.toJson(input == null ? Map.of() : input))
                .currentNodeCount(0)
                .completedNodeCount(0)
                .failedNodeCount(0)
                .eventSequence(0L)
                .cancelRequested(0)
                .version(0L)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        executionMapper.insert(execution);

        // Initialise every node as PENDING. The scheduler decides which ones become READY.
        int nodeCount = 0;
        for (WorkflowNodeDefinition node : document.nodes()) {
            NodeExecution nodeExecution = NodeExecution.builder()
                    .id(idGenerator.nextId())
                    .workflowExecutionId(executionId)
                    .nodeKey(node.getId())
                    .nodeType(node.getType().toUpperCase())
                    .status(NodeStatus.PENDING.name())
                    .currentAttempt(0)
                    .maxAttempts(resolveMaxAttempts(node))
                    .leaseToken(0L)
                    .version(0L)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            nodeExecutionMapper.insert(nodeExecution);
            nodeCount++;
        }

        execution.setCurrentNodeCount(nodeCount);
        executionMapper.updateById(execution);

        eventService.append(executionId, null, EventType.WORKFLOW_CREATED,
                Map.of("workflowId", Ids.of(version.getWorkflowId()),
                        "workflowVersionId", Ids.of(version.getId()),
                        "totalNodes", nodeCount), userId);

        MessageEnvelope<WorkflowStartedPayload> envelope = MessageEnvelope.create(
                MessageType.WORKFLOW_STARTED,
                WorkflowStartedPayload.builder()
                        .workflowId(version.getWorkflowId())
                        .workflowVersionId(version.getId())
                        .executionId(executionId)
                        .input(input == null ? Map.of() : input)
                        .build());
        envelope.setWorkflowExecutionId(executionId);
        envelope.setTraceId(traceId);
        outboxService.enqueue(MessageType.WORKFLOW_STARTED, "WORKFLOW", executionId, envelope);

        log.info("workflow execution created executionId={} workflowId={} nodes={}",
                executionId, version.getWorkflowId(), nodeCount);
        return execution;
    }

    /**
     * Moves the execution into the running state. This is done by the scheduler once it has
     * picked the execution up.
     */
    @Transactional
    public boolean markRunning(Long executionId) {
        WorkflowExecution execution = executionMapper.selectById(executionId);
        if (execution == null) {
            return false;
        }
        if (WorkflowStatus.RUNNING.name().equals(execution.getStatus())) {
            return true;
        }
        if (!WorkflowStatus.canTransition(WorkflowStatus.valueOf(execution.getStatus()), WorkflowStatus.RUNNING)) {
            return false;
        }
        int updated = executionMapper.compareAndSetStatus(executionId,
                execution.getStatus(),
                execution.getVersion(),
                WorkflowStatus.RUNNING.name(),
                LocalDateTime.now(),
                execution.getFinishedAt(),
                null, null);
        if (updated == 1) {
            eventService.append(executionId, null, EventType.WORKFLOW_STARTED, Map.of(), null);
        }
        return updated == 1;
    }

    public WorkflowExecution get(Long executionId) {
        WorkflowExecution execution = executionMapper.selectById(executionId);
        if (execution == null) {
            throw new BizException(ErrorCode.EX_NOT_FOUND);
        }
        return execution;
    }

    public WorkflowExecution requireAccessible(Long executionId, Long userId, boolean admin) {
        WorkflowExecution execution = get(executionId);
        if (!admin && !execution.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.EX_FORBIDDEN);
        }
        return execution;
    }

    /**
     * The node states of an execution. The JSON payloads are not loaded, because the list is only
     * used for the console and for the scheduling decisions.
     */
    public List<NodeExecution> nodes(Long executionId) {
        return nodeExecutionMapper.findStatesByWorkflowExecutionId(executionId);
    }

    /**
     * The node states of an execution including their outputs.
     *
     * <p>This is the variant that is used when the result of the workflow is assembled, because
     * the output of a node carries the data the next node and the report need.
     */
    public List<NodeExecution> nodesWithOutput(Long executionId) {
        return nodeExecutionMapper.findByWorkflowExecutionId(executionId);
    }

    public NodeExecution node(Long executionId, String nodeKey) {
        NodeExecution node = nodeExecutionMapper.findByExecutionAndKey(executionId, nodeKey);
        if (node == null) {
            throw new BizException(ErrorCode.EX_NODE_NOT_FOUND);
        }
        return node;
    }

    public PageResult<WorkflowExecution> page(int page, int size, String status,
                                              Long workflowId, Long userId, boolean admin) {
        LambdaQueryWrapper<WorkflowExecution> query = new LambdaQueryWrapper<WorkflowExecution>()
                .eq(!admin, WorkflowExecution::getUserId, userId)
                .eq(status != null && !status.isBlank(), WorkflowExecution::getStatus, status)
                .eq(workflowId != null, WorkflowExecution::getWorkflowId, workflowId)
                .orderByDesc(WorkflowExecution::getCreatedAt);
        Page<WorkflowExecution> result = executionMapper.selectPage(new Page<>(page, size), query);
        return PageResult.of(result.getTotal(), page, size, result.getRecords());
    }

    private int resolveMaxAttempts(WorkflowNodeDefinition node) {
        RetryPolicy policy = node.getRetryPolicy() == null
                ? RetryPolicy.defaultPolicy()
                : RetryPolicy.builder()
                        .maxAttempts(node.getRetryPolicy().getMaxAttempts())
                        .initialDelaySeconds(node.getRetryPolicy().getInitialDelaySeconds())
                        .maxDelaySeconds(node.getRetryPolicy().getMaxDelaySeconds())
                        .multiplier(node.getRetryPolicy().getMultiplier())
                        .build();
        return policy.resolvedMaxAttempts();
    }

    /**
     * Builds the human readable number of an execution.
     *
     * <p>The suffix is derived from the snowflake id, so it is unique. Taking only the lowest six
     * digits would collide as soon as two executions are created in the same second, so the whole
     * id is folded into a wider number instead.
     */
    private String buildExecutionNo(Long executionId) {
        return "FP-" + LocalDateTime.now().format(EXECUTION_NO_FORMAT) + "-"
                + String.format("%012d", Math.abs(executionId % 1_000_000_000_000L));
    }

    /**
     * Requests the cancellation of an execution.
     *
     * <p>The cancellation is cooperative. The flag is set first, so the workers see it, and the
     * nodes that have not started yet are cancelled right away. A running node finishes its
     * current attempt and is then cancelled. The execution reaches the state {@code CANCELED} once
     * no node is active any more.
     */
    @Transactional
    public void cancel(Long executionId, String reason, Long operatorId) {
        WorkflowExecution execution = get(executionId);
        WorkflowStatus current = WorkflowStatus.valueOf(execution.getStatus());
        if (current.isTerminal()) {
            throw new BizException(ErrorCode.EX_ILLEGAL_STATE,
                    "The execution already finished with the state " + current);
        }

        WorkflowExecution flag = new WorkflowExecution();
        flag.setId(executionId);
        flag.setCancelRequested(1);
        flag.setCancelReason(reason);
        executionMapper.updateById(flag);

        // The nodes that have not started yet can be cancelled immediately.
        List<String> cancelled = new ArrayList<>();
        for (NodeExecution node : nodeExecutionMapper.findStatesByWorkflowExecutionId(executionId)) {
            NodeStatus status = NodeStatus.valueOf(node.getStatus());
            if (status == NodeStatus.PENDING || status == NodeStatus.READY
                    || status == NodeStatus.RETRY_WAIT || status == NodeStatus.WAITING_HUMAN) {
                nodeExecutionMapper.cancel(node.getId());
                cancelled.add(node.getNodeKey());
            }
        }

        // A pending approval is not worth waiting for any more.
        cancelPendingApproval(executionId, reason);

        // A running agent is asked to stop.
        for (AgentInvocation invocation : invocationMapper.findByWorkflowExecutionId(executionId)) {
            if (!AgentInvocation.STATUS_SUCCEEDED.equals(invocation.getStatus())
                    && !AgentInvocation.STATUS_FAILED.equals(invocation.getStatus())) {
                invocationService.cancel(invocation.getId(), reason);
            }
        }

        eventService.append(executionId, null, EventType.WORKFLOW_CANCELED,
                Map.of("reason", reason, "cancelledNodes", cancelled, "requestedBy", operatorId), operatorId);

        // If nothing is running any more, the execution ends right away. A node that is still
        // running finishes its attempt first, and the final state is reached when it reports back.
        finalizeCancellation(executionId);

        log.info("workflow execution cancellation requested executionId={} reason={} cancelled={}",
                executionId, reason, cancelled);
    }

    /**
     * Moves a cancelled execution into its final state.
     *
     * <p>The cancellation is cooperative, so the execution only ends when no node can make
     * progress any more. This method is called when the cancellation is requested and again when
     * a node reports its result, which covers both cases.
     */
    @Transactional
    public void finalizeCancellation(Long executionId) {
        WorkflowExecution execution = get(executionId);
        if (execution.getCancelRequested() == null || execution.getCancelRequested() != 1) {
            return;
        }
        if (WorkflowStatus.valueOf(execution.getStatus()).isTerminal()) {
            return;
        }
        boolean anyActive = false;
        for (NodeExecution node : nodeExecutionMapper.findStatesByWorkflowExecutionId(executionId)) {
            NodeStatus status = NodeStatus.valueOf(node.getStatus());
            if (status == NodeStatus.PENDING || status == NodeStatus.READY
                    || status == NodeStatus.RUNNING || status == NodeStatus.RETRY_WAIT
                    || status == NodeStatus.WAITING_HUMAN) {
                anyActive = true;
                break;
            }
        }
        if (anyActive) {
            return;
        }
        int updated = executionMapper.compareAndSetStatus(executionId,
                execution.getStatus(),
                execution.getVersion(),
                WorkflowStatus.CANCELED.name(),
                execution.getStartedAt(),
                LocalDateTime.now(),
                null,
                execution.getCancelReason());
        if (updated != 1) {
            return;
        }
        log.info("workflow execution cancelled executionId={} reason={}",
                executionId, execution.getCancelReason());
    }

    /**
     * Cancels the approval that waits for a decision, because nobody is going to decide any more.
     */
    private void cancelPendingApproval(Long executionId, String reason) {
        ApprovalRecord pending = approvalMapper.findPendingByExecution(executionId);
        if (pending == null) {
            return;
        }
        approvalMapper.decide(pending.getId(), ApprovalRecord.STATUS_CANCELED,
                JsonUtils.toJson(Map.of("canceled", true)), reason, null);
    }

    /**
     * Resolves the node type of a node execution, tolerating an unknown stored value.
     */
    public NodeType resolveNodeType(NodeExecution node) {
        try {
            return NodeType.from(node.getNodeType());
        } catch (IllegalArgumentException ex) {
            return NodeType.CODE;
        }
    }
}
