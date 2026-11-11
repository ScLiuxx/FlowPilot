package com.flowpilot.execution.executor;

import com.flowpilot.execution.model.NodeType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves the executor for a node type.
 */
@Slf4j
@Component
public class NodeExecutorRegistry {

    private final Map<NodeType, NodeExecutor> executors = new EnumMap<>(NodeType.class);

    public NodeExecutorRegistry(List<NodeExecutor> executorList) {
        for (NodeExecutor executor : executorList) {
            NodeExecutor previous = executors.put(executor.supportType(), executor);
            if (previous != null) {
                log.warn("multiple executors registered for node type {}", executor.supportType());
            }
        }
        log.info("node executors registered: {}", executors.keySet());
    }

    public NodeExecutor get(NodeType type) {
        NodeExecutor executor = executors.get(type);
        if (executor == null) {
            throw new IllegalArgumentException("Unsupported node type: " + type);
        }
        return executor;
    }

    public boolean supports(NodeType type) {
        return executors.containsKey(type);
    }
}
