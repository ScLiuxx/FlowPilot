package com.flowpilot.messaging.protocol.payloads;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Payload of {@code WorkflowStarted}: tells the scheduler a workflow execution exists.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowStartedPayload {

    private Long workflowId;
    private Long workflowVersionId;
    private Long executionId;
    private Map<String, Object> input;
}
