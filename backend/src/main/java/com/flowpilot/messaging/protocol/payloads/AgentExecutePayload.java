package com.flowpilot.messaging.protocol.payloads;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Payload of {@code AgentExecute}. Sent from the Java backend to the Python agent service.
 *
 * <p>{@code threadId} is stable for the whole invocation. It must never be regenerated when the
 * message is redelivered, otherwise the agent would lose its checkpoint and start from scratch.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentExecutePayload {

    private String agentType;
    private String threadId;
    private Long invocationId;
    private Map<String, Object> input;
    private Map<String, Object> config;

    public static String threadIdFor(Long invocationId) {
        return "flowpilot-" + invocationId;
    }
}
