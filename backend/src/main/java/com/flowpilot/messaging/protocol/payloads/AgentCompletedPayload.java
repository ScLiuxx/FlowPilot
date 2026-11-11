package com.flowpilot.messaging.protocol.payloads;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Payload of {@code AgentCompleted}: the agent finished successfully.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentCompletedPayload {

    private Long invocationId;
    private String threadId;
    private String checkpointId;
    private Map<String, Object> result;
    private Usage usage;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Usage {
        private Long inputTokens;
        private Long outputTokens;
    }
}
