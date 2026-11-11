package com.flowpilot.common.trace;

import com.flowpilot.common.id.SnowflakeIdGenerator;
import com.flowpilot.common.trace.mapper.TraceSpanMapper;
import com.flowpilot.common.util.JsonUtils;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Creates the spans of the platform and stores them.
 *
 * <p>The span names follow the span tree of the architecture design:
 *
 * <pre>
 *   workflow.execution
 *       +-- workflow.scheduler
 *       +-- node.&lt;nodeKey&gt;
 *              +-- agent.graph
 *              +-- llm.call
 *              +-- tool.&lt;toolName&gt;
 * </pre>
 *
 * <p>A span inherits the trace id of the message it was created for. The trace id of an envelope
 * is a 32 character hex string, which is exactly the format OpenTelemetry uses, so the trace
 * continues across the process boundary without a translation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TracingService {

    private final Tracer tracer;
    private final TraceSpanMapper traceSpanMapper;
    private final SnowflakeIdGenerator idGenerator;
    private final TransactionTemplate transactionTemplate;

    /**
     * Starts a span.
     *
     * @param spanName            the name of the span
     * @param traceId             the trace of the work, empty starts a new trace
     * @param parentSpanId        the parent span, empty makes this span a root span
     * @param workflowExecutionId the workflow execution the span belongs to
     * @param nodeExecutionId     the node execution the span belongs to
     * @param attributes          the attributes of the span, they must not contain secrets
     */
    public TracedSpan start(String spanName,
                            String traceId,
                            String parentSpanId,
                            Long workflowExecutionId,
                            Long nodeExecutionId,
                            Map<String, Object> attributes) {
        String effectiveTraceId = traceId == null || traceId.isBlank()
                ? TraceContext.newTraceId() : traceId;
        String spanId = TraceIds.newSpanId();
        // The trace of the thread is restored when the span ends, so a span that runs inside a
        // request does not leak its trace into the work that follows it.
        String previousTraceId = TraceContext.currentTraceIdOrNull();
        String previousSpanId = TraceContext.currentSpanIdOrNull();
        TraceContext.setTraceId(effectiveTraceId);
        TraceContext.setSpanId(spanId);
        // The start of the span is a wall clock time. The monotonic clock is only used for the
        // duration, because it does not jump when the system time is corrected.
        LocalDateTime startedAt = LocalDateTime.now();
        long startNanos = System.nanoTime();

        io.opentelemetry.api.trace.SpanBuilder builder = tracer.spanBuilder(spanName)
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute("flowpilot.span_id", spanId);
        if (parentSpanId != null && !parentSpanId.isBlank()) {
            // The parent lives in another service or in another message, so it is a remote parent.
            builder.setParent(remoteParent(effectiveTraceId, parentSpanId));
        } else {
            builder.setNoParent();
        }
        if (attributes != null) {
            attributes.forEach((key, value) -> {
                if (value != null) {
                    builder.setAttribute(key, String.valueOf(value));
                }
            });
        }

        Span span = builder.startSpan();

        return new TracedSpan(spanName, effectiveTraceId, parentSpanId, spanId,
                workflowExecutionId, nodeExecutionId, attributes, startedAt, startNanos, span,
                previousTraceId, previousSpanId);
    }

    /**
     * The parent context of a span that was created in another service.
     */
    private Context remoteParent(String traceId, String spanId) {
        SpanContext parent = SpanContext.createFromRemoteParent(
                traceId, spanId, TraceFlags.getSampled(), TraceState.getDefault());
        return Context.root().with(Span.wrap(parent));
    }

    /**
     * Stores a finished span.
     *
     * <p>The write happens in its own transaction, so a rollback of the business transaction does
     * not lose the span. A span is a diagnostic record, it must never change the outcome of the
     * business work.
     */
    private void persist(TracedSpan handle, String errorCode) {
        try {
            LocalDateTime finishedAt = LocalDateTime.now();
            TraceSpan span = TraceSpan.builder()
                    .id(idGenerator.nextId())
                    .traceId(handle.traceId())
                    .parentSpanId(handle.parentSpanId())
                    .spanId(handle.spanId())
                    .spanName(handle.spanName())
                    .workflowExecutionId(handle.workflowExecutionId())
                    .nodeExecutionId(handle.nodeExecutionId())
                    .durationMs(Duration.ofNanos(System.nanoTime() - handle.startNanos()).toMillis())
                    .status(errorCode == null ? "OK" : "ERROR")
                    .attributes(JsonUtils.toJson(handle.allAttributes()))
                    .startedAt(handle.startedAt())
                    .finishedAt(finishedAt)
                    .createdAt(finishedAt)
                    .build();
            transactionTemplate.executeWithoutResult(status -> traceSpanMapper.insert(span));
        } catch (Exception ex) {
            log.warn("the span {} could not be stored: {}", handle.spanName(), ex.getMessage());
        }
    }

    /**
     * A span that is running.
     */
    public final class TracedSpan implements AutoCloseable {

        private final String spanName;
        private final String traceId;
        private final String parentSpanId;
        private final String spanId;
        private final Long workflowExecutionId;
        private final Long nodeExecutionId;
        private final Map<String, Object> attributes;
        private final LocalDateTime startedAt;
        private final long startNanos;
        private final Span span;
        private final String previousTraceId;
        private final String previousSpanId;
        private final Map<String, Object> collected = new LinkedHashMap<>();
        private Scope scope;
        private String errorCode;
        private boolean closed;

        private TracedSpan(String spanName, String traceId, String parentSpanId, String spanId,
                           Long workflowExecutionId, Long nodeExecutionId,
                           Map<String, Object> attributes, LocalDateTime startedAt,
                           long startNanos, Span span, String previousTraceId,
                           String previousSpanId) {
            this.spanName = spanName;
            this.traceId = traceId;
            this.parentSpanId = parentSpanId;
            this.spanId = spanId;
            this.workflowExecutionId = workflowExecutionId;
            this.nodeExecutionId = nodeExecutionId;
            this.attributes = attributes == null ? Map.of() : attributes;
            this.startedAt = startedAt;
            this.startNanos = startNanos;
            this.span = span;
            this.previousTraceId = previousTraceId;
            this.previousSpanId = previousSpanId;
        }

        public String traceId() {
            return traceId;
        }

        public String spanId() {
            return spanId;
        }

        public String parentSpanId() {
            return parentSpanId;
        }

        public Long workflowExecutionId() {
            return workflowExecutionId;
        }

        public Long nodeExecutionId() {
            return nodeExecutionId;
        }

        public String spanName() {
            return spanName;
        }

        public Map<String, Object> attributes() {
            return attributes;
        }

        Map<String, Object> allAttributes() {
            Map<String, Object> all = new LinkedHashMap<>(attributes);
            all.putAll(collected);
            return all;
        }

        long startNanos() {
            return startNanos;
        }

        LocalDateTime startedAt() {
            return startedAt;
        }

        Span span() {
            return span;
        }

        /**
         * Adds an attribute to the span.
         */
        public TracedSpan attribute(String key, Object value) {
            if (value != null) {
                collected.put(key, value);
                span.setAttribute(key, String.valueOf(value));
            }
            return this;
        }

        /**
         * Marks the span as failed.
         */
        public TracedSpan fail(String errorCode) {
            this.errorCode = errorCode;
            span.setStatus(StatusCode.ERROR, errorCode);
            return this;
        }

        /**
         * Makes the span the current one, so the nested spans become its children.
         */
        public TracedSpan makeCurrent() {
            this.scope = span.makeCurrent();
            return this;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (scope != null) {
                scope.close();
            }
            span.end();
            persist(this, errorCode);
            // The thread continues with the trace it had before the span, so a span does not
            // leak its trace into the work that follows it.
            if (previousTraceId == null) {
                TraceContext.clear();
            } else {
                TraceContext.setTraceId(previousTraceId);
                TraceContext.setSpanId(previousSpanId);
            }
        }
    }
}
