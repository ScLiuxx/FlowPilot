package com.flowpilot.messaging.config;

/**
 * Names of the exchange, queues and routing keys used across the platform.
 *
 * <p>All of them are declared identically by the Java backend and the Python agent service, so a
 * missing declaration on either side surfaces immediately at startup instead of silently dropping
 * messages.
 */
public final class RabbitTopology {

    /** Durable topic exchange, the single entry point for every message. */
    public static final String EXCHANGE = "flowpilot.events";

    /**
     * Dead letter exchange, receives the messages that exhausted their retries.
     *
     * <p>The name must match the one the agent service declares. A queue can only be declared
     * once with a given set of arguments, so a mismatch here makes the broker reject the
     * declaration of the shared queues.
     */
    public static final String DEAD_LETTER_EXCHANGE = "flowpilot.dead-letter";

    /** Routing key that every dead lettered message carries. */
    public static final String DEAD_LETTER_ROUTING_KEY = "flowpilot.dead-letter";

    public static final String QUEUE_WORKFLOW_SCHEDULER = "workflow.scheduler.queue";
    public static final String QUEUE_NODE_EXECUTE = "node.execute.queue";
    public static final String QUEUE_AGENT_EXECUTE = "agent.execute.queue";
    public static final String QUEUE_AGENT_CONTROL = "agent.control.queue";
    public static final String QUEUE_BACKEND_AGENT_EVENTS = "backend.agent-events.queue";
    public static final String QUEUE_DEAD_LETTER = "flowpilot.dead-letter.queue";

    private RabbitTopology() {
    }
}
