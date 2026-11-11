package com.flowpilot.common.exception;

import com.flowpilot.common.result.ErrorCode;
import lombok.Getter;

/**
 * Business exception carrying a stable error code. Internal diagnostic details are kept in
 * {@code diagnostics} and are never rendered to the caller.
 */
@Getter
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Object diagnostics;

    public BizException(ErrorCode errorCode) {
        this(errorCode, errorCode.getDefaultMessage(), null);
    }

    public BizException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public BizException(ErrorCode errorCode, String message, Object diagnostics) {
        super(message);
        this.errorCode = errorCode;
        this.diagnostics = diagnostics;
    }

    public static BizException notFound(ErrorCode code, String message) {
        return new BizException(code, message);
    }

    public static BizException conflict(ErrorCode code, String message) {
        return new BizException(code, message);
    }
}
