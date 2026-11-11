package com.flowpilot.execution.model;

/**
 * Supported workflow node types.
 *
 * <p>HTTP, CODE, CONDITION, HUMAN and MERGE are executed by the Java worker. AGENT is dispatched
 * to the Python agent service over RabbitMQ, and LLM is a simplified single call form of it.
 */
public enum NodeType {

    /** Calls an external HTTP API. */
    HTTP,
    /** Deterministic data processing. */
    CODE,
    /** Selects a branch based on a condition. */
    CONDITION,
    /** A single structured model call. */
    LLM,
    /** A multi step LangGraph agent execution. */
    AGENT,
    /** Waits for a human approval. */
    HUMAN,
    /** Merges the results of parallel branches. */
    MERGE;

    public static NodeType from(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Node type must not be null");
        }
        try {
            return NodeType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unsupported node type: " + value, ex);
        }
    }

    /**
     * Whether this node type is executed by the Python agent service.
     */
    public boolean isAgentBacked() {
        return this == AGENT || this == LLM;
    }
}
