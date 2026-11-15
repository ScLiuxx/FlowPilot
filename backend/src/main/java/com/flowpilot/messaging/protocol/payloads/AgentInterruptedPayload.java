package com.flowpilot.messaging.protocol.payloads;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Payload of {@code AgentInterrupted}: the agent paused and waits for a human decision.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentInterruptedPayload {

    private Long invocationId;
    private String threadId;
    private String checkpointId;
    private String interruptType;
    private InterruptData interruptData;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InterruptData {
        private String title;
        private String description;
        private String requestedAction;
        private Map<String, Object> payload;
        /**
         * The content of the decision the reviewer is asked to approve.
         *
         * <p>The summary, the risk level and the body of every section. Without it the reviewer
         * would have to approve a decision they cannot read.
         */
        private Map<String, Object> report;
    }
}
