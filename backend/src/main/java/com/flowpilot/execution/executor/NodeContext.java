package com.flowpilot.execution.executor;

import com.flowpilot.execution.entity.NodeExecution;
import com.flowpilot.execution.model.NodeType;
import com.flowpilot.workflow.definition.WorkflowNodeDefinition;
import lombok.Builder;
import lombok.Data;

import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Everything a node executor needs to run one node.
 *
 * <p>Upstream data is passed by value from the database, never through static state or a global
 * map, so a worker restart cannot lose it and two executions cannot mix their data.
 */
@Data
@Builder(toBuilder = true)
public class NodeContext {

    private Long workflowExecutionId;
    private Long nodeExecutionId;
    private Integer attemptNo;

    private String nodeKey;
    private NodeType nodeType;

    private Map<String, Object> config;
    private Map<String, Object> input;

    /** Outputs of the direct predecessors, keyed by node key. */
    private Map<String, Object> upstreamOutputs;

    private String traceId;

    /** The fencing token of the current lease. */
    private Long leaseToken;
    private String workerId;

    private Integer timeoutSeconds;

    /**
     * Extends the lease of the node, so a long running executor is not mistaken for a worker that
     * died. It returns false when the lease was lost, which means the result must not be committed.
     */
    private BooleanSupplier heartbeat;

    /**
     * Extends the lease of the node.
     *
     * @return false when the lease was lost
     */
    public boolean sendHeartbeat() {
        return heartbeat != null && Boolean.TRUE.equals(heartbeat.getAsBoolean());
    }

    public static NodeContext from(NodeExecution node,
                                   WorkflowNodeDefinition definition,
                                   Map<String, Object> input,
                                   Map<String, Object> upstreamOutputs) {
        return NodeContext.builder()
                .workflowExecutionId(node.getWorkflowExecutionId())
                .nodeExecutionId(node.getId())
                .attemptNo(node.getCurrentAttempt())
                .nodeKey(node.getNodeKey())
                .nodeType(NodeType.from(node.getNodeType()))
                .config(definition == null ? Map.of() : definition.config())
                .input(input == null ? Map.of() : input)
                .upstreamOutputs(upstreamOutputs == null ? Map.of() : upstreamOutputs)
                .timeoutSeconds(definition == null || definition.getTimeoutPolicy() == null
                        ? null : definition.getTimeoutPolicy().getTimeoutSeconds())
                .build();
    }
}
