package com.flowpilot.execution.scheduler;

import com.flowpilot.agent.service.AgentInvocationService;
import com.flowpilot.common.util.JsonUtils;
import com.flowpilot.execution.entity.NodeExecution;
import com.flowpilot.execution.entity.WorkflowExecution;
import com.flowpilot.execution.mapper.NodeExecutionMapper;
import com.flowpilot.execution.mapper.WorkflowExecutionMapper;
import com.flowpilot.execution.model.NodeType;
import com.flowpilot.workflow.definition.WorkflowDefinitionDocument;
import com.flowpilot.workflow.definition.WorkflowNodeDefinition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dispatches agent backed nodes to the Python agent service.
 *
 * <p>The dispatch happens in the scheduler rather than in the worker, because the agent call is
 * asynchronous. The scheduler creates the invocation, publishes the execute message and returns;
 * the agent service reports the result later.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentNodeDispatcher {

    private final AgentInvocationService invocationService;
    private final NodeExecutionMapper nodeExecutionMapper;
    private final WorkflowExecutionMapper executionMapper;

    /**
     * Publishes the execute message for an agent node.
     */
    public void dispatch(NodeExecution node, WorkflowNodeDefinition definition) {
        Map<String, Object> input = buildAgentInput(node, definition);

        String agentType = "REPOSITORY_ANALYST";
        if (definition.getConfig() != null && definition.getConfig().get("agentType") != null) {
            agentType = String.valueOf(definition.getConfig().get("agentType"));
        }
        invocationService.dispatch(node, definition, input, agentType);
    }

    /**
     * Builds the input of the agent from the workflow input and the outputs of the predecessors.
     */
    private Map<String, Object> buildAgentInput(NodeExecution node, WorkflowNodeDefinition definition) {
        WorkflowExecution execution = executionMapper.selectById(node.getWorkflowExecutionId());
        Map<String, Object> input = new LinkedHashMap<>();
        if (execution != null) {
            input.putAll(JsonUtils.toMap(execution.getInputJson()));
        }
        if (definition != null) {
            Map<String, Object> upstream = new LinkedHashMap<>();
            for (String dependency : definition.dependencies()) {
                NodeExecution dependencyNode = nodeExecutionMapper.findByExecutionAndKey(
                        node.getWorkflowExecutionId(), dependency);
                if (dependencyNode != null && dependencyNode.getOutputJson() != null) {
                    upstream.put(dependency, JsonUtils.toMap(dependencyNode.getOutputJson()));
                }
            }
            if (!upstream.isEmpty()) {
                input.put("upstream", upstream);
                // Flatten the upstream metrics so the agent receives the statistics directly.
                upstream.forEach((key, value) -> {
                    if (value instanceof Map<?, ?> map && map.containsKey("merged")) {
                        Object merged = map.get("merged");
                        if (merged instanceof Map<?, ?> mergedMap) {
                            ((Map<String, Object>) mergedMap).forEach(input::putIfAbsent);
                        }
                    }
                });
            }
        }
        return input;
    }

    /**
     * Whether a node type is dispatched to the agent service.
     */
    public boolean isAgentNode(WorkflowNodeDefinition definition) {
        if (definition == null || definition.getType() == null) {
            return false;
        }
        try {
            return NodeType.from(definition.getType()).isAgentBacked();
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
