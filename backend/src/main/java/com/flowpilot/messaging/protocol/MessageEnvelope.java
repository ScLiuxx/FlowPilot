package com.flowpilot.messaging.protocol;

import com.flowpilot.common.util.JsonUtils;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * Uniform message envelope. Mirrors {@code flowpilot-protocol/common/envelope.schema.json}.
 *
 * <p>The envelope only carries what is needed to correlate a message across services. Business
 * payloads that could be large are passed by reference (dataset id or storage key) rather than
 * inlined.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageEnvelope<T> {

    public static final String SCHEMA_VERSION = "1.0";

    private String schemaVersion;
    private String messageId;
    private String messageType;
    private String correlationId;
    private String causationId;
    private Long workflowExecutionId;
    private Long nodeExecutionId;
    private Integer attemptNo;
    private Long invocationId;
    private String traceId;
    /**
     * The span of the producer.
     *
     * <p>The consumer continues the trace as the child of this span, so the whole chain of one
     * execution ends up in a single trace.
     */
    private String spanId;
    private Instant timestamp;
    private String producer;
    private T payload;

    public static <T> MessageEnvelope<T> create(MessageType type, T payload) {
        return MessageEnvelope.<T>builder()
                .schemaVersion(SCHEMA_VERSION)
                .messageId(java.util.UUID.randomUUID().toString())
                .messageType(type.wireName())
                .correlationId(com.flowpilot.common.trace.TraceContext.newCorrelationId())
                .traceId(com.flowpilot.common.trace.TraceContext.currentTraceId())
                .spanId(com.flowpilot.common.trace.TraceContext.currentSpanId())
                .timestamp(Instant.now())
                .producer("flowpilot-backend")
                .payload(payload)
                .build();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> payloadAsMap() {
        if (payload == null) {
            return Map.of();
        }
        if (payload instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return JsonUtils.toMap(payload);
    }

    /**
     * Verifies the envelope carries the fields required by the protocol.
     */
    public void validate() {
        if (schemaVersion == null || schemaVersion.isBlank()) {
            throw new IllegalArgumentException("schemaVersion is required");
        }
        if (!schemaVersion.startsWith("1.")) {
            throw new UnsupportedProtocolVersionException(
                    "Unsupported schema version: " + schemaVersion);
        }
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("messageId is required");
        }
        if (messageType == null || messageType.isBlank()) {
            throw new IllegalArgumentException("messageType is required");
        }
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId is required");
        }
        if (workflowExecutionId == null) {
            throw new IllegalArgumentException("workflowExecutionId is required");
        }
        if (traceId == null || !traceId.matches("^[0-9a-f]{32}$")) {
            throw new IllegalArgumentException("traceId must be 32 hex characters");
        }
        if (timestamp == null) {
            throw new IllegalArgumentException("timestamp is required");
        }
        if (producer == null || producer.isBlank()) {
            throw new IllegalArgumentException("producer is required");
        }
    }
}
