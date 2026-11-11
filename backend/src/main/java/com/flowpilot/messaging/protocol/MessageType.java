package com.flowpilot.messaging.protocol;

import java.util.Locale;

/**
 * The message types exchanged between the backend, the Java worker and the Python agent service.
 *
 * <p>The names match the {@code messageType} enum in
 * {@code flowpilot-protocol/common/envelope.schema.json} and the routing keys are derived from
 * them so a new message type only needs to be declared once.
 */
public enum MessageType {

    WORKFLOW_STARTED("workflow.started"),
    NODE_EXECUTE("node.execute"),
    NODE_COMPLETED("node.completed"),
    NODE_FAILED("node.failed"),
    AGENT_EXECUTE("agent.execute"),
    AGENT_RESUME("agent.resume"),
    AGENT_CANCEL("agent.cancel"),
    AGENT_COMPLETED("agent.completed"),
    AGENT_FAILED("agent.failed"),
    AGENT_INTERRUPTED("agent.interrupted");

    private final String routingKey;

    MessageType(String routingKey) {
        this.routingKey = routingKey;
    }

    public String routingKey() {
        return routingKey;
    }

    /**
     * The name on the wire, e.g. {@code WORKFLOW_STARTED} becomes {@code WorkflowStarted}.
     *
     * <p>This is the value the protocol schema and the Python service use, so the two sides
     * cannot drift apart.
     */
    public String wireName() {
        String[] parts = name().split("_");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(part.charAt(0));
                builder.append(part.substring(1).toLowerCase(Locale.ROOT));
            } else {
                builder.append(part.charAt(0));
                builder.append(part.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return builder.toString();
    }

    public static MessageType fromRoutingKey(String routingKey) {
        for (MessageType type : values()) {
            if (type.routingKey.equals(routingKey)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown routing key: " + routingKey);
    }

    /**
     * Resolves a message type from the value found on the wire.
     */
    public static MessageType fromWireName(String wireName) {
        for (MessageType type : values()) {
            if (type.wireName().equals(wireName)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown message type: " + wireName);
    }
}
