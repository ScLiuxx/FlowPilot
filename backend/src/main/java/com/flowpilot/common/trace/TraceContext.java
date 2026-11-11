package com.flowpilot.common.trace;

import org.slf4j.MDC;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Holds the trace id of the work currently being performed.
 *
 * <p>The trace id is a 32 character hex string, which is directly usable as an OpenTelemetry trace
 * id, and it is propagated into every message envelope and execution event so a single task can be
 * followed across the Java backend, RabbitMQ and the Python agent service.
 *
 * <p>The span id identifies the current step inside the trace. It is 16 hex characters, the format
 * OpenTelemetry uses for a span. The span id of a message is derived from the message id, so a
 * redelivered message lands in the same span instead of creating a second one.
 */
public final class TraceContext {

    public static final String MDC_KEY = "traceId";
    public static final String MDC_SPAN_ID = "spanId";
    public static final String MDC_REQUEST_ID = "requestId";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> SPAN_ID = new ThreadLocal<>();

    private TraceContext() {
    }

    public static String currentTraceId() {
        String traceId = TRACE_ID.get();
        return traceId != null ? traceId : newTraceId();
    }

    /**
     * The trace id of the thread, or {@code null} when the thread does not run inside a trace.
     *
     * <p>The difference to {@link #currentTraceId()} matters when a span has to restore the state
     * of the thread: a generated trace id must not be restored as if it was a real one.
     */
    public static String currentTraceIdOrNull() {
        return TRACE_ID.get();
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

    /**
     * The span id of the current step, empty when the thread does not run inside a span.
     */
    public static String currentSpanId() {
        String spanId = SPAN_ID.get();
        return spanId == null ? "" : spanId;
    }

    /**
     * The span id of the thread, or {@code null} when the thread does not run inside a span.
     */
    public static String currentSpanIdOrNull() {
        return SPAN_ID.get();
    }

    /**
     * Binds the span id to the current thread and to the logging MDC.
     */
    public static void setSpanId(String spanId) {
        if (spanId == null || spanId.isBlank()) {
            SPAN_ID.remove();
            MDC.remove(MDC_SPAN_ID);
            return;
        }
        SPAN_ID.set(spanId);
        MDC.put(MDC_SPAN_ID, spanId);
    }

    public static void clear() {
        TRACE_ID.remove();
        SPAN_ID.remove();
        MDC.remove(MDC_KEY);
        MDC.remove(MDC_SPAN_ID);
        MDC.remove(MDC_REQUEST_ID);
    }

    /**
     * Derives a correlation id that is stable for one logical business operation.
     */
    public static String newCorrelationId() {
        return UUID.randomUUID().toString();
    }
}
