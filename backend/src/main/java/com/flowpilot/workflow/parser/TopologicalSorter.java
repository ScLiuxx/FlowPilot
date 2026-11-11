package com.flowpilot.workflow.parser;

import com.flowpilot.workflow.definition.WorkflowNodeDefinition;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Kahn topological sort used for cycle detection and dependency analysis.
 *
 * <p>The sort only yields one legal execution order. It must not be used as a serial task list:
 * independent branches may still run in parallel. The scheduler therefore keeps the full
 * dependency graph and uses the sort only to prove that the graph is acyclic.
 *
 * <p>Time complexity O(V + E), space complexity O(V + E).
 */
public final class TopologicalSorter {

    private TopologicalSorter() {
    }

    /**
     * @param nodes node map keyed by node id
     * @return the nodes in a valid topological order
     * @throws CycleDetectedException when the graph contains a cycle
     */
    public static List<String> sort(Map<String, WorkflowNodeDefinition> nodes) {
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> adjacency = new HashMap<>();

        for (String nodeId : nodes.keySet()) {
            indegree.put(nodeId, 0);
            adjacency.put(nodeId, new ArrayList<>());
        }

        for (WorkflowNodeDefinition node : nodes.values()) {
            for (String dependency : node.dependencies()) {
                if (!nodes.containsKey(dependency)) {
                    throw new IllegalArgumentException("Dependency not found: " + dependency);
                }
                if (dependency.equals(node.getId())) {
                    throw new IllegalArgumentException("Self dependency detected on node: " + node.getId());
                }
                adjacency.get(dependency).add(node.getId());
                indegree.merge(node.getId(), 1, Integer::sum);
            }
        }

        // Use a deterministic order so that two runs over the same definition produce the same
        // sequence, which keeps the definition hash stable.
        Deque<String> queue = new ArrayDeque<>();
        nodes.keySet().stream().sorted().filter(id -> indegree.get(id) == 0).forEach(queue::offer);

        List<String> result = new ArrayList<>();
        while (!queue.isEmpty()) {
            String current = queue.poll();
            result.add(current);
            for (String next : adjacency.get(current)) {
                int degree = indegree.get(next) - 1;
                indegree.put(next, degree);
                if (degree == 0) {
                    queue.offer(next);
                }
            }
        }

        if (result.size() != nodes.size()) {
            throw new CycleDetectedException(findCycle(nodes, adjacency));
        }
        return result;
    }

    /**
     * Finds one concrete cycle so the validation error can name the offending nodes.
     */
    private static List<String> findCycle(Map<String, WorkflowNodeDefinition> nodes,
                                          Map<String, List<String>> adjacency) {
        Map<String, Integer> state = new HashMap<>(); // 0 = unvisited, 1 = on stack, 2 = done
        List<String> stack = new ArrayList<>();

        for (String nodeId : nodes.keySet()) {
            if (state.getOrDefault(nodeId, 0) == 0) {
                List<String> cycle = dfs(nodeId, adjacency, state, stack);
                if (cycle != null) {
                    return cycle;
                }
            }
        }
        return List.of();
    }

    private static List<String> dfs(String nodeId,
                                    Map<String, List<String>> adjacency,
                                    Map<String, Integer> state,
                                    List<String> stack) {
        state.put(nodeId, 1);
        stack.add(nodeId);
        for (String next : adjacency.getOrDefault(nodeId, List.of())) {
            int nextState = state.getOrDefault(next, 0);
            if (nextState == 1) {
                int index = stack.indexOf(next);
                return new ArrayList<>(stack.subList(index, stack.size()));
            }
            if (nextState == 0) {
                List<String> cycle = dfs(next, adjacency, state, stack);
                if (cycle != null) {
                    return cycle;
                }
            }
        }
        stack.remove(stack.size() - 1);
        state.put(nodeId, 2);
        return null;
    }

    /**
     * Builds the adjacency map of the workflow, keyed by node id.
     */
    public static Map<String, List<String>> adjacencyOf(Map<String, WorkflowNodeDefinition> nodes) {
        Map<String, List<String>> adjacency = new LinkedHashMap<>();
        for (String nodeId : nodes.keySet()) {
            adjacency.put(nodeId, new ArrayList<>());
        }
        for (WorkflowNodeDefinition node : nodes.values()) {
            for (String dependency : node.dependencies()) {
                if (nodes.containsKey(dependency)) {
                    adjacency.get(dependency).add(node.getId());
                }
            }
        }
        return adjacency;
    }
}
