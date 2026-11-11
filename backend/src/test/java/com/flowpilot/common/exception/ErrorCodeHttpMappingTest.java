package com.flowpilot.common.exception;

import com.flowpilot.common.result.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests of the mapping between the business error codes and the HTTP status codes.
 *
 * <p>The mapping lives in a private method of the exception handler, so it is exercised here
 * through the handler itself.
 */
class ErrorCodeHttpMappingTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("maps a conflict onto 409")
    void mapsConflict() {
        assertThat(statusOf(ErrorCode.AP_ILLEGAL_STATE)).isEqualTo(HttpStatus.CONFLICT);
        assertThat(statusOf(ErrorCode.EX_CONCURRENT_UPDATE)).isEqualTo(HttpStatus.CONFLICT);
        assertThat(statusOf(ErrorCode.EX_ILLEGAL_STATE)).isEqualTo(HttpStatus.CONFLICT);
        assertThat(statusOf(ErrorCode.WF_VERSION_IMMUTABLE)).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("maps a missing resource onto 404")
    void mapsNotFound() {
        assertThat(statusOf(ErrorCode.AP_NOT_FOUND)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(statusOf(ErrorCode.EX_NOT_FOUND)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(statusOf(ErrorCode.WF_NOT_FOUND)).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("maps the authentication and authorisation errors")
    void mapsAuthErrors() {
        assertThat(statusOf(ErrorCode.COMMON_UNAUTHORIZED)).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(statusOf(ErrorCode.AUTH_TOKEN_INVALID)).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(statusOf(ErrorCode.COMMON_FORBIDDEN)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(statusOf(ErrorCode.WF_FORBIDDEN)).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("maps a validation error onto 400 and an internal error onto 500")
    void mapsValidationAndInternal() {
        assertThat(statusOf(ErrorCode.COMMON_VALIDATION_ERROR)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(statusOf(ErrorCode.WF_VALIDATION_ERROR)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(statusOf(ErrorCode.COMMON_INTERNAL_ERROR)).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(statusOf(ErrorCode.MQ_PUBLISH_FAILED)).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private HttpStatus statusOf(ErrorCode code) {
        try {
            Method method = GlobalExceptionHandler.class
                    .getDeclaredMethod("httpStatusOf", ErrorCode.class);
            method.setAccessible(true);
            return (HttpStatus) method.invoke(handler, code);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("cannot read the status mapping", ex);
        }
    }
}
