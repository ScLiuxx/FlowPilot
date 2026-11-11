package com.flowpilot.common.result;

import lombok.Getter;

/**
 * Business error codes. The structure is {@code MODULE-TYPE-NNN} as specified in the protocol
 * design document.
 */
@Getter
public enum ErrorCode {

    SUCCESS(0, "success"),

    // ---- common ----
    COMMON_VALIDATION_ERROR(40000, "Request parameter validation failed"),
    COMMON_NOT_FOUND(40400, "Resource not found"),
    COMMON_CONFLICT(40900, "Resource conflict"),
    COMMON_INTERNAL_ERROR(50000, "Internal server error"),
    COMMON_UNAUTHORIZED(40100, "Authentication required"),
    COMMON_FORBIDDEN(40300, "Access denied"),

    // ---- user / auth ----
    USER_NOT_FOUND(40401, "User not found"),
    USER_ALREADY_EXISTS(40901, "Username or email already registered"),
    USER_DISABLED(40301, "User account is disabled"),
    AUTH_INVALID_CREDENTIALS(40101, "Invalid username or password"),
    AUTH_TOKEN_INVALID(40102, "Invalid or expired token"),
    AUTH_TOKEN_MISSING(40103, "Token is required"),

    // ---- workflow ----
    WF_VALIDATION_ERROR(40010, "Workflow definition is invalid"),
    WF_CYCLE_DETECTED(40011, "Circular dependency detected in workflow"),
    WF_NOT_FOUND(40410, "Workflow not found"),
    WF_VERSION_NOT_FOUND(40411, "Workflow version not found"),
    WF_VERSION_NOT_PUBLISHED(40012, "Workflow version is not published"),
    WF_VERSION_IMMUTABLE(40910, "Published workflow version cannot be modified"),
    WF_DUPLICATE_DEFINITION(40911, "An identical workflow definition already exists"),
    WF_FORBIDDEN(40310, "Not allowed to access this workflow"),

    // ---- execution ----
    EX_NOT_FOUND(40420, "Execution not found"),
    EX_ILLEGAL_STATE_TRANSITION(40923, "Illegal execution state transition"),
    EX_ILLEGAL_STATE(40922, "The execution is not in a state that allows this operation"),
    EX_CONCURRENT_UPDATE(40920, "Concurrent modification detected, please retry"),
    EX_LEASE_LOST(40921, "Execution lease has been lost"),
    EX_RETRY_EXHAUSTED(40021, "Retry attempts exhausted"),
    EX_NODE_NOT_FOUND(40421, "Node execution not found"),
    EX_NODE_ALREADY_TERMINAL(40924, "Node execution already reached a terminal state"),
    EX_FORBIDDEN(40320, "Not allowed to access this execution"),

    // ---- messaging ----
    MQ_PUBLISH_FAILED(50010, "Failed to publish message"),
    MQ_CONSUME_FAILED(50011, "Failed to consume message"),
    MQ_UNSUPPORTED_SCHEMA(40010, "Unsupported message schema version"),
    MQ_INVALID_ENVELOPE(40011, "Invalid message envelope"),

    // ---- agent ----
    AG_INVOCATION_NOT_FOUND(40430, "Agent invocation not found"),
    AG_TIMEOUT(40030, "Agent execution timed out"),
    AG_CHECKPOINT_FAILED(50030, "Failed to restore agent checkpoint"),
    AG_BUDGET_EXCEEDED(40031, "Agent execution budget exceeded"),
    AG_TYPE_NOT_SUPPORTED(40032, "Unsupported agent type"),

    // ---- approval ----
    AP_NOT_FOUND(40440, "Approval record not found"),
    AP_ILLEGAL_STATE(40940, "Approval is not in a state that accepts a decision"),
    AP_FORBIDDEN(40340, "Not allowed to decide this approval"),

    // ---- report ----
    RP_NOT_FOUND(40450, "Report not found"),
    RP_GENERATE_FAILED(50050, "Failed to generate report"),
    RP_FORBIDDEN(40350, "Not allowed to access this report");

    private final int code;
    private final String defaultMessage;

    ErrorCode(int code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }
}
