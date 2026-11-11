package com.flowpilot.execution.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

public final class ExecutionDtos {

    private ExecutionDtos() {
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StartExecutionRequest {
        private Long workflowVersionId;

        private Map<String, Object> input;

        /** Optional key that makes a repeated start request idempotent. */
        private String idempotencyKey;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StartExecutionResponse {
        private Long executionId;
        private String executionNo;
        private String status;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExecutionResponse {
        private Long executionId;
        private String executionNo;
        private Long workflowId;
        private Long workflowVersionId;
        private Long userId;
        private String status;
        private Map<String, Object> input;
        private Map<String, Object> output;
        private Integer totalNodes;
        private Integer completedNodes;
        private Integer failedNodes;
        private String errorCode;
        private String errorMessage;
        private String startedAt;
        private String finishedAt;
        private String createdAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NodeResponse {
        private Long nodeExecutionId;
        private String nodeKey;
        private String nodeType;
        private String status;
        private Integer attempt;
        private Integer maxAttempts;
        private String workerId;
        private String startedAt;
        private String finishedAt;
        private Long durationMs;
        private String errorCode;
        private String errorMessage;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AttemptResponse {
        private Integer attemptNo;
        private String status;
        private String workerId;
        private String errorCode;
        private String errorMessage;
        private String startedAt;
        private String finishedAt;
        private Long durationMs;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EventResponse {
        private Long sequenceNo;
        private String eventType;
        private Long nodeExecutionId;
        private Map<String, Object> eventData;
        private String createdAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CancelRequest {
        private String reason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CancelResponse {
        private Long executionId;
        private String status;
    }
}
