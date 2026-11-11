package com.flowpilot.messaging.protocol.payloads;

import com.flowpilot.messaging.protocol.ErrorPayload;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payload of {@code NodeFailed}. Carries the error classification the scheduler needs to decide
 * between a retry and a terminal failure.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NodeFailedPayload {

    private String nodeKey;
    private String errorCode;
    private String errorCategory;
    private String errorMessage;
    private Boolean retryable;

    public static NodeFailedPayload from(ErrorPayload error, String nodeKey) {
        return NodeFailedPayload.builder()
                .nodeKey(nodeKey)
                .errorCode(error.getErrorCode())
                .errorCategory(error.getErrorCategory().name())
                .errorMessage(error.getErrorMessage())
                .retryable(error.isRetryable())
                .build();
    }
}
