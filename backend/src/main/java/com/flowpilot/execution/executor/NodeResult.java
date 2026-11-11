package com.flowpilot.execution.executor;

import com.flowpilot.messaging.protocol.ErrorCategory;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * The outcome of a node execution.
 */
@Data
@Builder
public class NodeResult {

    private boolean success;
    private Map<String, Object> output;
    private String errorCode;
    private ErrorCategory errorCategory;
    private String errorMessage;
    private boolean retryable;

    public static NodeResult success(Map<String, Object> output) {
        return NodeResult.builder()
                .success(true)
                .output(output == null ? Map.of() : output)
                .build();
    }

    public static NodeResult failure(String errorCode, ErrorCategory category,
                                     String errorMessage, boolean retryable) {
        return NodeResult.builder()
                .success(false)
                .errorCode(errorCode)
                .errorCategory(category)
                .errorMessage(errorMessage)
                .retryable(retryable)
                .build();
    }
}
