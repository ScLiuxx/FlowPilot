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
     *
     * <p>A dependency that was skipped does not block the node: the branch it belongs to was not
     * selected, so the node that waits for it must not wait forever either.
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
            if (status != NodeStatus.SUCCEEDED && status != NodeStatus.SKIPPED) {
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

    /**
     * Returns the nodes that belong to a branch of a condition node.
     *
     * <p>The condition node declares its branches in the config:
     *
     * <pre>
     *   "config": { "expression": "x == 1", "branches": { "true": ["a"], "false": ["b"] } }
     * </pre>
     *
     * <p>A node that is not mentioned in any branch belongs to no branch, so it is never skipped.
     */
    public List<String> branchNodes(WorkflowNodeDefinition condition, String branch) {
        Map<String, Object> config = condition.config();
        Object branches = config.get("branches");
        if (!(branches instanceof Map<?, ?> map)) {
            return List.of();
        }
        Object nodes = map.get(branch);
        if (!(nodes instanceof Iterable<?> iterable)) {
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        iterable.forEach(item -> keys.add(String.valueOf(item)));
        return keys;
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
