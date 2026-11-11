package com.flowpilot.messaging.protocol.payloads;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payload of {@code AgentFailed}. The agent service reports the failure, the backend decides
 * whether the workflow retries or fails.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentFailedPayload {

    private Long invocationId;
    private String threadId;
    private String checkpointId;
    private String errorCode;
    private String errorCategory;
    private String errorMessage;
    private Boolean retryable;
}
