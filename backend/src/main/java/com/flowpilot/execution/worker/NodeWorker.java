package com.flowpilot.execution.worker;

import com.flowpilot.approval.service.ApprovalService;
import com.flowpilot.common.id.SnowflakeIdGenerator;
import com.flowpilot.common.metrics.FlowPilotMetrics;
import com.flowpilot.common.trace.TracingService;
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
import com.flowpilot.workflow.parser.NodeSchemaRegistry;
import com.flowpilot.workflow.service.WorkflowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

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
    private final TracingService tracingService;
    private final FlowPilotMetrics metrics;

    @Value("${flowpilot.worker.id:worker-01}")
    private String workerId;

    /** Whether the worker extends the lease of a running node. */
    @Value("${flowpilot.worker.heartbeat-enabled:true}")
    private boolean heartbeatEnabled;

    /** The interval between two heartbeats. It has to be shorter than the lease duration. */
    @Value("${flowpilot.worker.heartbeat-interval-seconds:20}")
    private long heartbeatIntervalSeconds;

    /**
     * Handles one NodeExecute message.
     *
     * @param payload       the message payload
     * @param executionId   the workflow execution
     * @param nodeExecutionId the node execution
     * @param attemptNo     the attempt the message asks for
     * @param traceId       the trace of the execution
     * @param parentSpanId  the span of the message that triggered the execution
     */
    public void execute(NodeExecutePayload payload, Long executionId, Long nodeExecutionId,
                        Integer attemptNo, String traceId, String parentSpanId) {
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

        // The lease is extended while the node runs, so a node that takes longer than the lease
        // duration is not mistaken for a worker that died.
        Heartbeat heartbeat = startHeartbeat(refreshed, leaseToken);

        try (TracingService.TracedSpan span = tracingService.start("node." + refreshed.getNodeKey(),
                traceId, parentSpanId, refreshed.getWorkflowExecutionId(), refreshed.getId(),
                Map.of("nodeKey", refreshed.getNodeKey(),
                        "nodeType", refreshed.getNodeType(),
                        "attempt", effectiveAttempt,
                        "worker", workerId))) {
            span.makeCurrent();
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
                List<String> violations = NodeSchemaRegistry.validate(
                        NodeSchemaRegistry.outputSchema(definition == null ? null : definition.config()),
                        result.getOutput());
                if (!violations.isEmpty()) {
                    // The node produced something that does not fit its own schema. Reporting it
                    // as a failure is better than storing data the successor cannot read.
                    span.fail("NODE_OUTPUT_SCHEMA_VIOLATION");
                    resultService.fail(refreshed.getId(), effectiveAttempt, leaseToken,
                            "NODE_OUTPUT_SCHEMA_VIOLATION",
                            "The output of the node does not fit the declared schema: "
                                    + String.join("; ", violations),
                            ErrorCategory.VALIDATION.name(), false, null);
                    recordNodeMetrics(refreshed, nodeType, NodeStatus.FAILED.name(), span);
                    return;
                }
                resultService.complete(refreshed.getId(), effectiveAttempt, leaseToken, result.getOutput());
                recordNodeMetrics(refreshed, nodeType, NodeStatus.SUCCEEDED.name(), span);
            } else {
                span.fail(result.getErrorCode());
                resultService.fail(refreshed.getId(), effectiveAttempt, leaseToken,
                        result.getErrorCode(), result.getErrorMessage(),
                        result.getErrorCategory() == null ? ErrorCategory.INTERNAL.name()
                                : result.getErrorCategory().name(),
                        result.isRetryable(),
                        definition == null ? null : definition.getRetryPolicy());
                recordNodeMetrics(refreshed, nodeType, NodeStatus.FAILED.name(), span);
            }
        } catch (Exception ex) {
            log.error("node {} execution threw an unexpected exception", nodeExecutionId, ex);
            resultService.fail(refreshed.getId(), effectiveAttempt, leaseToken,
                    "NODE_UNEXPECTED_ERROR", ex.getMessage(), ErrorCategory.INTERNAL.name(), true,
                    null);
        } finally {
            heartbeat.stop();
        }
    }

    /**
     * Reports the duration of a node execution.
     */
    private void recordNodeMetrics(NodeExecution node, NodeType nodeType, String status,
                                   TracingService.TracedSpan span) {
        if (node.getStartedAt() != null) {
            long millis = Duration.between(node.getStartedAt(), LocalDateTime.now()).toMillis();
            metrics.recordNodeDuration(nodeType.name(), status, millis);
        }
        metrics.countNodeExecution(nodeType.name(), status);
        span.attribute("flowpilot.node_status", status);
    }

    /**
     * Starts the heartbeat that keeps the lease of a running node alive.
     */
    private Heartbeat startHeartbeat(NodeExecution node, Long leaseToken) {
        if (!heartbeatEnabled) {
            return Heartbeat.disabled();
        }
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "heartbeat-" + node.getNodeKey());
            thread.setDaemon(true);
            return thread;
        });
        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(() -> {
            try {
                if (!leaseManager.heartbeat(node, workerId, leaseToken)) {
                    log.warn("the lease of node {} was lost, its result will not be committed",
                            node.getNodeKey());
                }
            } catch (Exception ex) {
                log.warn("the heartbeat of node {} failed: {}", node.getNodeKey(), ex.getMessage());
            }
        }, heartbeatIntervalSeconds, heartbeatIntervalSeconds, TimeUnit.SECONDS);
        return new Heartbeat(scheduler, task);
    }

    /**
     * The heartbeat of one running node.
     */
    private static final class Heartbeat {

        private final ScheduledExecutorService scheduler;
        private final ScheduledFuture<?> task;

        private Heartbeat(ScheduledExecutorService scheduler, ScheduledFuture<?> task) {
            this.scheduler = scheduler;
            this.task = task;
        }

        static Heartbeat disabled() {
            return new Heartbeat(null, null);
        }

        void stop() {
            if (task != null) {
                task.cancel(false);
            }
            if (scheduler != null) {
                scheduler.shutdownNow();
            }
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
                // A long running node extends its lease while it works, so the recovery scanner
                // does not mistake a busy worker for a worker that died.
                .heartbeat(() -> leaseManager.heartbeat(node, workerId, leaseToken))
                .build();
    }

    private WorkflowDefinitionDocument loadDocument(Long workflowExecutionId) {
        WorkflowExecution execution = executionMapper.selectById(workflowExecutionId);
        if (execution == null) {
            return new WorkflowDefinitionDocument();
        }
        WorkflowVersion version = workflowService.getVersionInternal(execution.getWorkflowId(),
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
