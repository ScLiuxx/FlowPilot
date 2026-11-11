package com.flowpilot.execution.worker;

import com.flowpilot.approval.service.ApprovalService;
import com.flowpilot.common.id.SnowflakeIdGenerator;
import com.flowpilot.common.util.JsonUtils;
import com.flowpilot.execution.entity.NodeExecution;
import com.flowpilot.execution.entity.NodeExecutionAttempt;
import com.flowpilot.execution.entity.WorkflowExecution;
import com.flowpilot.execution.executor.NodeContext;
import com.flowpilot.execution.executor.NodeExecutor;
import com.flowpilot.execution.executor.NodeExecutorRegistry;
import com.flowpilot.execution.executor.NodeResult;
import com.flowpilot.execution.mapper.NodeExecutionAttemptMapper;
import com.flowpilot.execution.mapper.NodeExecutionMapper;
import com.flowpilot.execution.mapper.WorkflowExecutionMapper;
import com.flowpilot.execution.model.NodeStatus;
import com.flowpilot.execution.model.NodeType;
import com.flowpilot.execution.model.WorkflowStatus;
import com.flowpilot.execution.recovery.LeaseManager;
import com.flowpilot.execution.service.NodeResultService;
import com.flowpilot.messaging.protocol.ErrorCategory;
import com.flowpilot.messaging.protocol.payloads.NodeExecutePayload;
import com.flowpilot.workflow.definition.WorkflowDefinitionDocument;
import com.flowpilot.workflow.definition.WorkflowNodeDefinition;
import com.flowpilot.workflow.entity.WorkflowVersion;
import com.flowpilot.workflow.service.WorkflowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Executes a node on behalf of the worker pool.
 *
 * <p>The worker never trusts the message payload as the source of truth. It reads the node
 * execution from the database, acquires the lease, and only then runs the executor. This is what
 * keeps a redelivered message from producing a second side effect.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NodeWorker {

    private final NodeExecutionMapper nodeExecutionMapper;
    private final NodeExecutionAttemptMapper attemptMapper;
    private final WorkflowExecutionMapper executionMapper;
    private final NodeExecutorRegistry registry;
    private final LeaseManager leaseManager;
    private final NodeResultService resultService;
    private final WorkflowService workflowService;
    private final ApprovalService approvalService;
    private final SnowflakeIdGenerator idGenerator;

    @Value("${flowpilot.worker.id:worker-01}")
    private String workerId;

    /**
     * Handles one NodeExecute message.
     *
     * @param payload       the message payload
     * @param executionId   the workflow execution
     * @param nodeExecutionId the node execution
     * @param attemptNo     the attempt the message asks for
     */
    public void execute(NodeExecutePayload payload, Long executionId, Long nodeExecutionId,
                        Integer attemptNo) {
        NodeExecution node = nodeExecutionMapper.selectById(nodeExecutionId);
        if (node == null) {
            log.warn("node execution {} does not exist, ignoring the message", nodeExecutionId);
            return;
        }
        if (NodeStatus.valueOf(node.getStatus()).isTerminal()) {
            log.info("node {} already reached the terminal state {}, ignoring the message",
                    node.getId(), node.getStatus());
            return;
        }
        if ("RUNNING".equals(node.getStatus())
                && node.getCurrentAttempt() != null && attemptNo != null
                && node.getCurrentAttempt() >= attemptNo) {
            log.info("node {} is already running attempt {}, ignoring the duplicate message",
                    node.getId(), node.getCurrentAttempt());
            return;
        }

        int effectiveAttempt = attemptNo == null
                ? (node.getCurrentAttempt() == null ? 1 : node.getCurrentAttempt() + 1)
                : attemptNo;

        Long leaseToken = leaseManager.acquire(node, workerId, effectiveAttempt,
                node.getCurrentAttempt() == null ? 0 : node.getCurrentAttempt());
        if (leaseToken == null) {
            log.info("node {} could not be leased by {}, another worker owns it", node.getId(), workerId);
            return;
        }

        NodeExecution refreshed = nodeExecutionMapper.selectById(nodeExecutionId);
        resultService.startAttempt(refreshed, effectiveAttempt, leaseToken, workerId);

        try {
            WorkflowDefinitionDocument document = loadDocument(refreshed.getWorkflowExecutionId());
            WorkflowNodeDefinition definition = findNode(document, refreshed.getNodeKey());
            NodeType nodeType = NodeType.from(refreshed.getNodeType());

            if (nodeType.isAgentBacked()) {
                // Agent nodes are dispatched to the Python service by the agent module. The Java
                // worker must not block a thread while a model is running.
                throw new IllegalStateException(
                        "Agent backed node " + node.getNodeKey() + " must be dispatched by the agent module");
            }

            NodeExecutor executor = registry.get(nodeType);
            NodeContext context = buildContext(refreshed, definition, effectiveAttempt, leaseToken);
            NodeResult result = executor.execute(context);

            if (nodeType == NodeType.HUMAN) {
                // A human node pauses the workflow. The approval service creates the request and
                // the workflow continues once the decision arrives.
                pauseForApproval(refreshed, definition, result);
                return;
            }

            if (result.isSuccess()) {
                resultService.complete(refreshed.getId(), effectiveAttempt, leaseToken, result.getOutput());
            } else {
                resultService.fail(refreshed.getId(), effectiveAttempt, leaseToken,
                        result.getErrorCode(), result.getErrorMessage(),
                        result.getErrorCategory() == null ? ErrorCategory.INTERNAL.name()
                                : result.getErrorCategory().name(),
                        result.isRetryable(),
                        definition == null ? null : definition.getRetryPolicy());
            }
        } catch (Exception ex) {
            log.error("node {} execution threw an unexpected exception", nodeExecutionId, ex);
            resultService.fail(refreshed.getId(), effectiveAttempt, leaseToken,
                    "NODE_UNEXPECTED_ERROR", ex.getMessage(), ErrorCategory.INTERNAL.name(), true,
                    null);
        }
    }

    /**
     * Turns a human node into a pending approval.
     *
     * <p>The node does not finish here. It waits in {@code WAITING_HUMAN} until a reviewer
     * decides, and the workflow stays in {@code WAITING_HUMAN} as well.
     */
    private void pauseForApproval(NodeExecution node, WorkflowNodeDefinition definition,
                                   NodeResult result) {
        Map<String, Object> config = definition == null ? Map.of() : definition.config();
        Map<String, Object> output = result == null ? Map.of() : result.getOutput();

        int attemptNo = node.getCurrentAttempt() == null ? 1 : node.getCurrentAttempt();
        boolean paused = resultService.markWaitingHuman(node.getId(), attemptNo);
        if (!paused) {
            log.warn("the human node {} could not be put into the waiting state", node.getId());
            return;
        }

        // The approval request also moves the workflow into the waiting state, because a workflow
        // that waits for a reviewer is not a failure.
        approvalService.request(node.getWorkflowExecutionId(), node.getId(),
                String.valueOf(config.getOrDefault("approvalType", "GENERIC")),
                String.valueOf(output.getOrDefault("title",
                        config.getOrDefault("title", "请确认执行结果"))),
                String.valueOf(output.getOrDefault("description",
                        config.getOrDefault("description", ""))),
                output);

        log.info("the human node {} waits for a decision", node.getNodeKey());
    }

    private NodeContext buildContext(NodeExecution node, WorkflowNodeDefinition definition,
                                     int attemptNo, Long leaseToken) {
        Map<String, Object> upstreamOutputs = new LinkedHashMap<>();
        if (definition != null) {
            for (String dependency : definition.dependencies()) {
                NodeExecution dependencyNode = nodeExecutionMapper.findByExecutionAndKey(
                        node.getWorkflowExecutionId(), dependency);
                if (dependencyNode != null && dependencyNode.getOutputJson() != null) {
                    upstreamOutputs.put(dependency, JsonUtils.toMap(dependencyNode.getOutputJson()));
                }
            }
        }
        Map<String, Object> input = JsonUtils.toMap(node.getInputJson());
        return NodeContext.from(node, definition, input, upstreamOutputs).toBuilder()
                .attemptNo(attemptNo)
                .leaseToken(leaseToken)
                .workerId(workerId)
                .build();
    }

    private WorkflowDefinitionDocument loadDocument(Long workflowExecutionId) {
        WorkflowExecution execution = executionMapper.selectById(workflowExecutionId);
        if (execution == null) {
            return new WorkflowDefinitionDocument();
        }
        WorkflowVersion version = workflowService.getVersion(execution.getWorkflowId(),
                execution.getWorkflowVersionId());
        return workflowService.parse(version.getDefinitionJson());
    }

    private WorkflowNodeDefinition findNode(WorkflowDefinitionDocument document, String nodeKey) {
        for (WorkflowNodeDefinition node : document.nodes()) {
            if (node.getId().equals(nodeKey)) {
                return node;
            }
        }
        return null;
    }
}
