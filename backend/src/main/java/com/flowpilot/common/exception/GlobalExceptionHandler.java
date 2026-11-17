package com.flowpilot.common.exception;

import com.flowpilot.common.result.ErrorCode;
import com.flowpilot.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.io.IOException;
import java.util.stream.Collectors;

/**
 * Translates exceptions into the unified response envelope.
 *
 * <p>Internal messages and stack traces are logged but never returned to the caller, so database
 * errors or model internals cannot leak to the console.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBizException(BizException ex) {
        ErrorCode code = ex.getErrorCode();
        if (code.getCode() >= 50000) {
            log.error("business failure code={} message={}", code.getCode(), ex.getMessage(), ex);
        } else {
            log.warn("business rejection code={} message={}", code.getCode(), ex.getMessage());
        }
        return ResponseEntity.status(httpStatusOf(code)).body(Result.failure(code, ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(this::describe)
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest()
                .body(Result.failure(ErrorCode.COMMON_VALIDATION_ERROR, message));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest()
                .body(Result.failure(ErrorCode.COMMON_VALIDATION_ERROR, message));
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Result<Void>> handleBadRequest(Exception ex) {
        return ResponseEntity.badRequest()
                .body(Result.failure(ErrorCode.COMMON_VALIDATION_ERROR, ex.getMessage()));
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<Result<Void>> handleNotFound(NoHandlerFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Result.failure(ErrorCode.COMMON_NOT_FOUND, "Resource not found"));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Result<Void>> handleAuthentication(AuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Result.failure(ErrorCode.AUTH_TOKEN_INVALID, "Invalid or expired token"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<Void>> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Result.failure(ErrorCode.COMMON_FORBIDDEN, "Access denied"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnexpected(Exception ex, HttpServletResponse response) {
        if (isClientDisconnect(ex)) {
            // A closed tab or a stream whose client went away is not a server failure:
            // there is nobody left to answer, and the stack trace is only noise.
            log.debug("the client disconnected before the response was complete: {}", ex.getMessage());
            return null;
        }
        if (response.isCommitted()) {
            // The response is already on the wire, as happens for an event stream. Writing
            // the envelope would fail and bury the original error under a second one.
            log.warn("the response was already committed: {}", ex.getMessage());
            return null;
        }
        log.error("unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.failure(ErrorCode.COMMON_INTERNAL_ERROR, "Internal server error"));
    }

    /**
     * Whether the failure only says that the client is gone.
     */
    private boolean isClientDisconnect(Exception ex) {
        if (ex instanceof IOException) {
            String message = ex.getMessage();
            return message != null
                    && (message.contains("Broken pipe") || message.contains("Connection reset"));
        }
        String type = ex.getClass().getName();
        return type.endsWith("ClientAbortException")
                || type.endsWith("AsyncRequestNotUsableException");
    }

    private String describe(FieldError error) {
        return error.getField() + " " + error.getDefaultMessage();
    }

    private HttpStatus httpStatusOf(ErrorCode code) {
        if (code.getCode() == ErrorCode.SUCCESS.getCode()) {
            return HttpStatus.OK;
        }
        int value = code.getCode();
        if (value >= 50000) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        // The conflict range is checked before the not found range, because a conflict code is
        // numerically larger than a not found code.
        if (value >= 40900) {
            return HttpStatus.CONFLICT;
        }
        if (value >= 40400) {
            return HttpStatus.NOT_FOUND;
        }
        if (value >= 40300) {
            return HttpStatus.FORBIDDEN;
        }
        if (value >= 40100) {
            return HttpStatus.UNAUTHORIZED;
        }
        return HttpStatus.BAD_REQUEST;
    }
}
