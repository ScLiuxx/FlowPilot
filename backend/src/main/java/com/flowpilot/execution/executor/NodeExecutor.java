package com.flowpilot.execution.executor;

import com.flowpilot.execution.model.NodeType;

/**
 * Strategy interface for the node types the Java worker can execute.
 *
 * <p>Registering an executor for a node type is enough to make the type available; the registry
 * resolves it at runtime, so adding a new node type does not require changing a switch statement.
 */
public interface NodeExecutor {

    NodeType supportType();

    /**
     * Executes the node. Implementations must not throw for expected business failures; they
     * return a failed {@link NodeResult} instead. Unexpected exceptions are caught by the worker.
     */
    NodeResult execute(NodeContext context);
}
