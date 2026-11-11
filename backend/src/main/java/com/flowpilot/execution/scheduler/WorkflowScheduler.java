package com.flowpilot.execution.scheduler;

import com.flowpilot.common.trace.TraceContext;
import com.flowpilot.common.util.JsonUtils;
import com.flowpilot.common.util.SafeUrlValidator;
import com.flowpilot.execution.entity.NodeExecution;
import com.flowpilot.execution.entity.WorkflowExecution;
import com.flowpilot.execution.mapper.NodeExecutionMapper;
import com.flowpilot.execution.mapper.WorkflowExecutionMapper;
import com.flowpilot.execution.model.EventType;
import com.flowpilot.execution.model.NodeStatus;
import com.flowpilot.execution.model.NodeType;
import com.flowpilot.execution.model.WorkflowStatus;
import com.flowpilot.execution.service.ExecutionEventService;
import com.flowpilot.execution.service.WorkflowExecutionService;
import com.flowpilot.messaging.outbox.OutboxService;
import com.flowpilot.messaging.protocol.MessageEnvelope;
import com.flowpilot.messaging.protocol.MessageType;
import com.flowpilot.messaging.protocol.payloads.NodeExecutePayload;
import com.flowpilot.workflow.definition.WorkflowDefinitionDocument;
import com.flowpilot.workflow.definition.WorkflowNodeDefinition;
import com.flowpilot.workflow.entity.WorkflowVersion;
import com.flowpilot.workflow.service.WorkflowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Decides what runs next.
 *
 * <p>The scheduler never executes a node itself. It only changes a node from {@code PENDING} to
 * {@code READY} and writes the outbox message that hands the work to a worker. Keeping the
 * decision and the execution apart is what allows the worker pool to scale independently.
 *
 * <p>Concurrent schedulers are safe: the transition from {@code PENDING} to {@code READY} is a
 * conditional update, so exactly one scheduler wins and only one execution message is produced.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowScheduler {

    private final WorkflowService workflowService;
    private final WorkflowExecutionService executionService;
    private final NodeExecutionMapper nodeExecutionMapper;
    private final WorkflowExecutionMapper executionMapper;
    private final DependencyResolver dependencyResolver;
    private final ExecutionEventService eventService;
    private final OutboxService outboxService;
    private final AgentNodeDispatcher agentNodeDispatcher;

    /**
     * Entry point for a freshly created execution: finds the start nodes and schedules them.
     */
    @Transactional
    public void onWorkflowStarted(Long executionId) {
        WorkflowExecution execution = executionService.get(executionId);
        if (WorkflowStatus.valueOf(execution.getStatus()).isTerminal()) {
            log.info("ignoring start message for terminal execution {}", executionId);
            return;
        }
        executionService.markRunning(executionId);
        scheduleInitialNodes(executionId);
    }

    /**
     * Schedules every node whose dependencies are satisfied.
     */
    @Transactional
    public void scheduleInitialNodes(Long executionId) {
        WorkflowExecution execution = executionService.get(executionId);
        WorkflowDefinitionDocument document = loadDocument(execution);
        List<NodeExecution> nodes = executionService.nodes(executionId);
        for (NodeExecution node : nodes) {
            if (!NodeStatus.PENDING.name().equals(node.getStatus())) {
                continue;
            }
            WorkflowNodeDefinition definition = dependencyResolver.findNode(document, node.getNodeKey());
            if (definition == null || !definition.dependencies().isEmpty()) {
                continue;
            }
            dispatch(node, definition, document, execution);
        }
    }

    /**
     * Called after a node finished successfully: schedules the successors whose dependencies are
     * now all satisfied.
     */
    @Transactional
    public void onNodeCompleted(Long executionId, String nodeKey) {
        WorkflowExecution execution = executionService.get(executionId);
        if (WorkflowStatus.valueOf(execution.getStatus()).isTerminal()) {
            return;
        }
        WorkflowDefinitionDocument document = loadDocument(execution);
        List<String> successors = dependencyResolver.successors(document, nodeKey);
        for (String successorKey : successors) {
            NodeExecution successor = nodeExecutionMapper.findByExecutionAndKey(executionId, successorKey);
            if (successor == null || !NodeStatus.PENDING.name().equals(successor.getStatus())) {
                continue;
            }
            WorkflowNodeDefinition definition = dependencyResolver.findNode(document, successorKey);
            if (definition == null) {
                continue;
            }
            if (!dependencyResolver.canExecute(successor, document)) {
                continue;
            }
            dispatch(successor, definition, document, execution);
        }
        checkCompletion(executionId);
    }

    /**
     * Schedules a node that was in retry wait once its delay elapsed.
     */
    @Transactional
    public void scheduleRetry(NodeExecution node) {
        WorkflowExecution execution = executionService.get(node.getWorkflowExecutionId());
        WorkflowDefinitionDocument document = loadDocument(execution);
        WorkflowNodeDefinition definition = dependencyResolver.findNode(document, node.getNodeKey());
        if (definition == null) {
            return;
        }
        // The dependencies were satisfied when the node first ran, so it can go straight to READY.
        int updated = nodeExecutionMapper.markRetryReady(node.getId());
        if (updated != 1) {
            return;
        }
        node.setStatus(NodeStatus.READY.name());
        publishNodeExecute(node, definition, execution, buildNodeInput(execution, definition));
    }

    /**
     * Changes a node to READY and publishes the execution message.
     *
     * <p>Both happen in the same transaction, so a node can never end up READY without a
     * corresponding message, and a message can never be published for a node that is not READY.
     */
    private void dispatch(NodeExecution node,
                          WorkflowNodeDefinition definition,
                          WorkflowDefinitionDocument document,
                          WorkflowExecution execution) {
        Map<String, Object> input = buildNodeInput(execution, definition);
        int updated = nodeExecutionMapper.markReady(node.getId(), JsonUtils.toJson(input));
        if (updated != 1) {
            // Another scheduler already dispatched this node.
            return;
        }
        node.setStatus(NodeStatus.READY.name());
        node.setInputJson(JsonUtils.toJson(input));
        eventService.append(node.getWorkflowExecutionId(), node.getId(), EventType.NODE_READY,
                Map.of("nodeKey", node.getNodeKey(), "nodeType", node.getNodeType()), null);
        publishNodeExecute(node, definition, execution, input);
    }

    private void publishNodeExecute(NodeExecution node,
                                    WorkflowNodeDefinition definition,
                                    WorkflowExecution execution,
                                    Map<String, Object> input) {
        NodeType nodeType = NodeType.from(definition.getType());

        if (nodeType.isAgentBacked()) {
            // Agent nodes are executed by the Python service. The scheduler dispatches them
            // asynchronously and the node stays in the running state until the agent reports back.
            agentNodeDispatcher.dispatch(node, definition);
            return;
        }

        NodeExecutePayload payload = NodeExecutePayload.builder()
                .nodeKey(node.getNodeKey())
                .nodeType(nodeType.name())
                .config(definition.config())
                .input(input)
                .timeoutSeconds(definition.getTimeoutPolicy() == null
                        ? null : definition.getTimeoutPolicy().getTimeoutSeconds())
                .build();

        MessageEnvelope<NodeExecutePayload> envelope = MessageEnvelope.create(MessageType.NODE_EXECUTE, payload);
        envelope.setWorkflowExecutionId(node.getWorkflowExecutionId());
        envelope.setNodeExecutionId(node.getId());
        envelope.setAttemptNo(node.getCurrentAttempt() == null ? 1 : node.getCurrentAttempt() + 1);
        envelope.setTraceId(TraceContext.currentTraceId());
        outboxService.enqueue(MessageType.NODE_EXECUTE, "NODE", node.getId(), envelope);
    }

    /**
     * Builds the input of a node from the workflow input and the outputs of its predecessors.
     */
    private Map<String, Object> buildNodeInput(WorkflowExecution execution,
                                               WorkflowNodeDefinition definition) {
        Map<String, Object> input = new java.util.LinkedHashMap<>();
        Map<String, Object> workflowInput = JsonUtils.toMap(execution.getInputJson());
        input.putAll(workflowInput);
        enrichWithRepositoryReference(input);

        Map<String, Object> upstream = new java.util.LinkedHashMap<>();
        for (String dependency : definition.dependencies()) {
            NodeExecution dependencyNode = nodeExecutionMapper.findByExecutionAndKey(
                    execution.getId(), dependency);
            if (dependencyNode != null && dependencyNode.getOutputJson() != null) {
                upstream.put(dependency, JsonUtils.toMap(dependencyNode.getOutputJson()));
            }
        }
        if (!upstream.isEmpty()) {
            input.put("upstream", upstream);
        }
        return input;
    }

    /**
     * Derives the repository owner and name from the submitted repository url.
     *
     * <p>The user only submits the url, but the GitHub API is called with the owner and the
     * repository name. Deriving them once here keeps the node configuration free of the details
     * of the input format.
     */
    private void enrichWithRepositoryReference(Map<String, Object> input) {
        Object url = input.get("repositoryUrl");
        if (url == null) {
            return;
        }
        boolean hasOwner = input.get("owner") != null && !String.valueOf(input.get("owner")).isBlank();
        boolean hasRepo = input.get("repo") != null && !String.valueOf(input.get("repo")).isBlank();
        if (hasOwner && hasRepo) {
            return;
        }
        try {
            SafeUrlValidator.RepoRef ref =
                    SafeUrlValidator.validateGitHubRepositoryUrl(String.valueOf(url));
            input.putIfAbsent("owner", ref.owner());
            input.putIfAbsent("repo", ref.name());
        } catch (IllegalArgumentException ex) {
            // The url is validated again when the node runs. An unusable url must not stop the
            // workflow here, the node reports the precise reason.
            log.debug("the repository url could not be parsed: {}", ex.getMessage());
        }
    }

    /**
     * Redispatches an agent node after its worker lease expired.
     *
     * <p>The invocation is kept, so the agent service continues from its checkpoint instead of
     * starting the analysis from scratch.
     */
    @Transactional
    public void scheduleAgentRecovery(NodeExecution node, WorkflowNodeDefinition definition) {
        WorkflowExecution execution = executionService.get(node.getWorkflowExecutionId());
        int updated = nodeExecutionMapper.markRetryReady(node.getId());
        if (updated != 1) {
            return;
        }
        node.setStatus(NodeStatus.READY.name());
        agentNodeDispatcher.dispatch(node, definition);
        log.info("agent node {} redispatched after a lease recovery", node.getNodeKey());
    }

    /**
     * Marks the execution as finished when no node can make progress any more.
     */
    @Transactional
    public void checkCompletion(Long executionId) {
        WorkflowExecution execution = executionService.get(executionId);
        if (WorkflowStatus.valueOf(execution.getStatus()).isTerminal()) {
            return;
        }
        List<NodeExecution> nodes = executionService.nodes(executionId);
        boolean anyActive = false;
        boolean anyFailed = false;
        boolean anyWaitingHuman = false;
        int completed = 0;
        int failed = 0;

        for (NodeExecution node : nodes) {
            NodeStatus status = NodeStatus.valueOf(node.getStatus());
            switch (status) {
                case PENDING, READY, RUNNING, RETRY_WAIT -> anyActive = true;
                case WAITING_HUMAN -> anyWaitingHuman = true;
                case FAILED, CANCELED -> {
                    failed++;
                    anyFailed = true;
                }
                case SUCCEEDED, SKIPPED -> completed++;
                default -> anyActive = true;
            }
        }

        if (anyFailed) {
            failWorkflow(execution, nodes);
            return;
        }
        if (anyActive) {
            return;
        }
        if (anyWaitingHuman) {
            return;
        }
        if (completed == nodes.size()) {
            succeedWorkflow(execution, nodes);
        }
    }

    private void succeedWorkflow(WorkflowExecution execution, List<NodeExecution> nodes) {
        Map<String, Object> output = new java.util.LinkedHashMap<>();
        for (NodeExecution node : nodes) {
            if (node.getOutputJson() != null) {
                output.put(node.getNodeKey(), JsonUtils.toMap(node.getOutputJson()));
            }
        }
        int updated = executionMapper.compareAndSetStatus(execution.getId(),
                execution.getStatus(),
                execution.getVersion(),
                WorkflowStatus.SUCCEEDED.name(),
                execution.getStartedAt(),
                LocalDateTime.now(),
                null, null);
        if (updated != 1) {
            return;
        }
        executionMapper.updateById(WorkflowExecution.builder()
                .id(execution.getId())
                .outputJson(JsonUtils.toJson(output))
                .completedNodeCount(nodes.size())
                .failedNodeCount(0)
                .build());
        eventService.append(execution.getId(), null, EventType.WORKFLOW_SUCCEEDED,
                Map.of("totalNodes", nodes.size()), null);
        log.info("workflow execution succeeded executionId={}", execution.getId());
    }

    /**
     * Fails the workflow and cancels every node that has not started yet.
     */
    private void failWorkflow(WorkflowExecution execution, List<NodeExecution> nodes) {
        List<String> cancelled = new ArrayList<>();
        for (NodeExecution node : nodes) {
            NodeStatus status = NodeStatus.valueOf(node.getStatus());
            if (status == NodeStatus.PENDING || status == NodeStatus.READY || status == NodeStatus.RETRY_WAIT) {
                nodeExecutionMapper.cancel(node.getId());
                cancelled.add(node.getNodeKey());
            }
        }
        int failed = 0;
        for (NodeExecution node : nodes) {
            if (NodeStatus.valueOf(node.getStatus()) == NodeStatus.FAILED) {
                failed++;
            }
        }
        int updated = executionMapper.compareAndSetStatus(execution.getId(),
                execution.getStatus(),
                execution.getVersion(),
                WorkflowStatus.FAILED.name(),
                execution.getStartedAt(),
                LocalDateTime.now(),
                "NODE_EXECUTION_FAILED",
                "The workflow failed because " + failed + " node(s) could not be completed");
        if (updated != 1) {
            return;
        }
        executionMapper.updateById(WorkflowExecution.builder()
                .id(execution.getId())
                .failedNodeCount(failed)
                .build());
        eventService.append(execution.getId(), null, EventType.WORKFLOW_FAILED,
                Map.of("failedNodes", failed, "cancelledNodes", cancelled), null);
        log.info("workflow execution failed executionId={} failedNodes={}", execution.getId(), failed);
    }

    private WorkflowDefinitionDocument loadDocument(WorkflowExecution execution) {
        WorkflowVersion version = workflowService.getVersion(execution.getWorkflowId(),
                execution.getWorkflowVersionId());
        return workflowService.parse(version.getDefinitionJson());
    }
}
