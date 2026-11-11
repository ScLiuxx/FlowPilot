package com.flowpilot.messaging.protocol;

/**
 * Raised when a consumer receives a message whose major protocol version it does not implement.
 * Such messages must be quarantined rather than silently processed.
 */
public class UnsupportedProtocolVersionException extends RuntimeException {

    public UnsupportedProtocolVersionException(String message) {
        super(message);
    }
}
