package com.flowpilot.common.result;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;

import java.time.Instant;

/**
 * Unified REST response envelope. See {@code docs/api-contract.md}.
 *
 * @param <T> payload type
 */
@Getter
@JsonInclude(JsonInclude.Include.ALWAYS)
public class Result<T> {

    private final int code;
    private final String message;
    private final T data;
    private final String requestId;
    private final String timestamp;

    private Result(int code, String message, T data, String requestId) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.requestId = requestId;
        this.timestamp = Instant.now().toString();
    }

    public static <T> Result<T> success(T data) {
        return new Result<>(ErrorCode.SUCCESS.getCode(), "success", data, RequestIdHolder.get());
    }

    public static Result<Void> success() {
        return success(null);
    }

    public static <T> Result<T> failure(ErrorCode errorCode, String message) {
        return new Result<>(errorCode.getCode(), message, null, RequestIdHolder.get());
    }

    public static <T> Result<T> failure(ErrorCode errorCode) {
        return failure(errorCode, errorCode.getDefaultMessage());
    }
}
