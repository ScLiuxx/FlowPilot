package com.flowpilot.messaging.protocol.payloads;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

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
    /**
     * The details of the failure, e.g. the moment the rate limit of an upstream API resets.
     *
     * <p>The backend uses them to postpone the retry: an upstream that says "come back in an
     * hour" must not be called again in ten seconds.
     */
    private Map<String, Object> diagnostics;
}
