package com.flowpilot.messaging.protocol.payloads;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Payload of {@code AgentResume}: a human approved a paused agent, continue it.
 *
 * <p>{@code resumeRequestId} is the idempotency key. The same approval can only resume the agent
 * once, even if the message is redelivered or the user double clicks the approve button.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentResumePayload {

    private Long invocationId;
    private String threadId;
    private String resumeRequestId;
    private Long approvalId;
    private Map<String, Object> resumeValue;
}
