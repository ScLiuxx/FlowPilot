package com.flowpilot.messaging.protocol;

/**
 * Error classification used to decide the retry behaviour of a failed execution.
 *
 * <p>Not every failure should be retried. A validation error will never succeed on a retry, while
 * a network timeout usually will.
 */
public enum ErrorCategory {

    /** Transient infrastructure problem, retry with backoff. */
    TRANSIENT,
    /** The remote service asked us to slow down, retry after the requested delay. */
    RATE_LIMIT,
    /** The input was wrong, retrying cannot help. */
    VALIDATION,
    /** The caller is not allowed to perform the operation. */
    PERMISSION,
    /** A domain rule rejected the operation, the business decides. */
    BUSINESS,
    /** An unexpected internal error, limited retry and alert. */
    INTERNAL;

    public boolean isRetryableByDefault() {
        return this == TRANSIENT || this == RATE_LIMIT || this == INTERNAL;
    }
}
