package com.flowpilot.workflow.parser;

import java.util.List;

/**
 * Raised when the dependency graph of a workflow contains a cycle.
 */
public class CycleDetectedException extends RuntimeException {

    private final List<String> cycle;

    public CycleDetectedException(List<String> cycle) {
        super(cycle == null || cycle.isEmpty()
                ? "Cycle detected in workflow"
                : "Cycle detected in workflow: " + String.join(" -> ", cycle));
        this.cycle = cycle == null ? List.of() : List.copyOf(cycle);
    }

    public List<String> getCycle() {
        return cycle;
    }
}
