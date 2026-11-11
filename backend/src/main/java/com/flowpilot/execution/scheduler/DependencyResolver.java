package com.flowpilot.execution.scheduler;

import com.flowpilot.execution.entity.NodeExecution;
import com.flowpilot.execution.mapper.NodeExecutionMapper;
import com.flowpilot.execution.model.NodeStatus;
import com.flowpilot.workflow.definition.WorkflowDefinitionDocument;
import com.flowpilot.workflow.definition.WorkflowNodeDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides whether a node may run.
 *
 * <p>The rule is simple: a node may run when every direct predecessor succeeded.
 *
 * <pre>
 *   Ready(v) = 1 if for all u in Pred(v): Status(u) = SUCCEEDED
 * </pre>
 *
 * <p>The check reads the authoritative state from the database instead of an in memory map, so a
 * scheduler that runs in a different process sees the same truth.
 */
@Component
@RequiredArgsConstructor
public class DependencyResolver {

    private final NodeExecutionMapper nodeExecutionMapper;

    /**
     * @return true when all dependencies of the node succeeded
     */
    public boolean canExecute(NodeExecution node, WorkflowDefinitionDocument document) {
        WorkflowNodeDefinition definition = findNode(document, node.getNodeKey());
        if (definition == null) {
            return false;
        }
        List<String> dependencies = definition.dependencies();
        if (dependencies.isEmpty()) {
            return true;
        }
        Map<String, NodeStatus> statuses = loadStatuses(node.getWorkflowExecutionId());
        for (String dependency : dependencies) {
            NodeStatus status = statuses.get(dependency);
            if (status != NodeStatus.SUCCEEDED) {
                return false;
            }
        }
        return true;
    }

    /**
     * Loads the status of every node of one execution.
     */
    public Map<String, NodeStatus> loadStatuses(Long workflowExecutionId) {
        Map<String, NodeStatus> statuses = new LinkedHashMap<>();
        for (NodeExecution node : nodeExecutionMapper.findStatesByWorkflowExecutionId(workflowExecutionId)) {
            statuses.put(node.getNodeKey(), NodeStatus.valueOf(node.getStatus()));
        }
        return statuses;
    }

    /**
     * Returns the nodes that directly depend on the given node key.
     */
    public List<String> successors(WorkflowDefinitionDocument document, String nodeKey) {
        List<String> successors = new ArrayList<>();
        for (WorkflowNodeDefinition node : document.nodes()) {
            if (node.dependencies().contains(nodeKey)) {
                successors.add(node.getId());
            }
        }
        return successors;
    }

    /**
     * Returns the nodes without any dependency, the entry points of the graph.
     */
    public List<String> startNodes(WorkflowDefinitionDocument document) {
        List<String> starts = new ArrayList<>();
        for (WorkflowNodeDefinition node : document.nodes()) {
            if (node.dependencies().isEmpty()) {
                starts.add(node.getId());
            }
        }
        return starts;
    }

    public WorkflowNodeDefinition findNode(WorkflowDefinitionDocument document, String nodeKey) {
        for (WorkflowNodeDefinition node : document.nodes()) {
            if (node.getId().equals(nodeKey)) {
                return node;
            }
        }
        return null;
    }
}
