package com.flowpilot.messaging.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Uniform error body. Mirrors {@code flowpilot-protocol/common/error.schema.json}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.ALWAYS)
public class ErrorPayload {

    private String errorCode;
    private ErrorCategory errorCategory;
    private String errorMessage;
    private boolean retryable;
    private Map<String, Object> diagnostics;

    public static ErrorPayload of(ErrorCategory category, String code, String message) {
        return ErrorPayload.builder()
                .errorCode(code)
                .errorCategory(category)
                .errorMessage(message)
                .retryable(category.isRetryableByDefault())
                .build();
    }
}
