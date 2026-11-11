package com.flowpilot.execution.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
    private final ExecutionEventService eventService;
    private final OutboxService outboxService;
    private final SnowflakeIdGenerator idGenerator;

    /**
     * Starts a new execution of a published workflow version.
     */
    @Transactional
    public WorkflowExecution start(WorkflowVersion version,
                                   WorkflowDefinitionDocument document,
                                   Long userId,
                                   Map<String, Object> input) {
        Long executionId = idGenerator.nextId();
        WorkflowExecution execution = WorkflowExecution.builder()
                .id(executionId)
                .workflowId(version.getWorkflowId())
                .workflowVersionId(version.getId())
                .userId(userId)
                .executionNo(buildExecutionNo(executionId))
                .status(WorkflowStatus.CREATED.name())
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
                Map.of("workflowId", version.getWorkflowId(),
                        "workflowVersionId", version.getId(),
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

    private String buildExecutionNo(Long executionId) {
        return "FP-" + LocalDateTime.now().format(EXECUTION_NO_FORMAT) + "-"
                + String.format("%06d", Math.abs(executionId % 1_000_000));
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
