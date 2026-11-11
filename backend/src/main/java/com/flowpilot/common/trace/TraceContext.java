package com.flowpilot.common.trace;

import org.slf4j.MDC;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Holds the trace id of the work currently being performed.
 *
 * <p>The trace id is a 32 character hex string, which is directly usable as an OpenTelemetry trace
 * id, and it is propagated into every message envelope and execution event so a single task can
 * be followed across the Java backend, RabbitMQ and the Python agent service.
 */
public final class TraceContext {

    public static final String MDC_KEY = "traceId";
    public static final String MDC_REQUEST_ID = "requestId";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();

    private TraceContext() {
    }

    public static String currentTraceId() {
        String traceId = TRACE_ID.get();
        return traceId != null ? traceId : newTraceId();
    }

    public static String newTraceId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        char[] hex = new char[32];
        for (int i = 0; i < 16; i++) {
            hex[i * 2] = HEX[(bytes[i] >> 4) & 0x0F];
            hex[i * 2 + 1] = HEX[bytes[i] & 0x0F];
        }
        return new String(hex);
    }

    /**
     * Binds the trace id to the current thread and to the logging MDC.
     */
    public static void setTraceId(String traceId) {
        TRACE_ID.set(traceId);
        MDC.put(MDC_KEY, traceId);
    }

    public static void clear() {
        TRACE_ID.remove();
        MDC.remove(MDC_KEY);
        MDC.remove(MDC_REQUEST_ID);
    }

    /**
     * Derives a correlation id that is stable for one logical business operation.
     */
    public static String newCorrelationId() {
        return UUID.randomUUID().toString();
    }
}
