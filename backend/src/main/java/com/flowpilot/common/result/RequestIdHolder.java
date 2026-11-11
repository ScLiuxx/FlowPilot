package com.flowpilot.common.result;

/**
 * Holds the id of the request currently being served, so the response envelope can echo it.
 */
public final class RequestIdHolder {

    private static final ThreadLocal<String> REQUEST_ID = new ThreadLocal<>();

    private RequestIdHolder() {
    }

    public static void set(String requestId) {
        REQUEST_ID.set(requestId);
    }

    public static String get() {
        String value = REQUEST_ID.get();
        return value == null ? "-" : value;
    }

    public static void clear() {
        REQUEST_ID.remove();
    }
}
