package com.flowpilot.messaging.protocol.payloads;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payload of {@code AgentCancel}: a best effort request to stop a running agent.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentCancelPayload {

    private Long invocationId;
    private String threadId;
    private String cancelRequestId;
    private String cancelReason;
}
