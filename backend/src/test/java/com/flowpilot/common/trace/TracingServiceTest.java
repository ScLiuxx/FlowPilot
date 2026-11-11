package com.flowpilot.common.trace;

import com.flowpilot.common.id.SnowflakeIdGenerator;
import com.flowpilot.common.trace.mapper.TraceSpanMapper;
import com.flowpilot.common.util.JsonUtils;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests of the tracing service.
 *
 * <p>The span times are stored in the database, so a wrong clock would show a wrong chain in the
 * console. The monotonic clock measures the duration, the wall clock the moment the span started:
 * mixing the two would shift the start by the uptime of the machine.
 */
class TracingServiceTest {

    /** A valid trace id, the format the protocol requires. */
    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    private TracingService service() {
        OpenTelemetry openTelemetry = OpenTelemetry.noop();
        Tracer tracer = openTelemetry.getTracer("test");
        TraceSpanMapper mapper = mock(TraceSpanMapper.class);
        when(mapper.insert(any(TraceSpan.class))).thenReturn(1);
        TransactionTemplate transactionTemplate = new TransactionTemplate();
        transactionTemplate.setTransactionManager(new org.springframework.transaction.support
                .AbstractPlatformTransactionManager() {
            @Override
            protected Object doGetTransaction() {
                return new Object();
            }

            @Override
            protected void doBegin(Object transaction, org.springframework.transaction
                    .TransactionDefinition definition) {
                // No transaction is needed for the test.
            }

            @Override
            protected void doCommit(org.springframework.transaction.support
                    .DefaultTransactionStatus status) {
                // No transaction is needed for the test.
            }

            @Override
            protected void doRollback(org.springframework.transaction.support
                    .DefaultTransactionStatus status) {
                // No transaction is needed for the test.
            }
        });
        return new TracingService(tracer, mapper, new SnowflakeIdGenerator(), transactionTemplate);
    }

    @Test
    @DisplayName("the span starts at the moment it was created")
    void theSpanStartsNow() {
        LocalDateTime before = LocalDateTime.now();
        TracingService.TracedSpan span = service().start("node.test", TRACE_ID, "",
                1L, 2L, Map.of("key", "value"));
        LocalDateTime after = LocalDateTime.now();
        span.close();

        assertThat(span.startedAt()).isBetween(before.minusSeconds(1), after.plusSeconds(1));
    }

    @Test
    @DisplayName("the duration of the span is measured with the monotonic clock")
    void theDurationIsMeasured() {
        TracingService.TracedSpan span = service().start("node.test", TRACE_ID, "",
                1L, 2L, Map.of());
        try {
            Thread.sleep(30);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        long duration = Duration.ofNanos(System.nanoTime() - span.startNanos()).toMillis();
        span.close();

        assertThat(duration).isGreaterThanOrEqualTo(25);
    }

    @Test
    @DisplayName("the span id is derived from the message id in the same way as the agent service")
    void theSpanIdIsStable() {
        String messageId = "4bf92f3577b34da6a3ce929d0e0e4736";
        // The Python agent service derives the span id the same way (the first sixteen hex
        // characters of the sha256 of the message id), so the two sides agree on the parent of a
        // message without putting it into the envelope.
        assertThat(TraceIds.spanIdOf(messageId)).isEqualTo("398acaee65c4e533");
        assertThat(TraceIds.spanIdOf(messageId)).isEqualTo(TraceIds.spanIdOf(messageId));
    }

    @Test
    @DisplayName("an empty message id produces a random span id")
    void anEmptyMessageIdProducesARandomSpanId() {
        assertThat(TraceIds.spanIdOf("")).matches("^[0-9a-f]{16}$");
        assertThat(TraceIds.spanIdOf(null)).matches("^[0-9a-f]{16}$");
    }

    @Test
    @DisplayName("a new trace id is 32 hex characters")
    void aNewTraceIdIsHex() {
        assertThat(TraceContext.newTraceId()).matches("^[0-9a-f]{32}$");
    }

    @Test
    @DisplayName("the attributes of the span are stored as JSON")
    void theAttributesAreStoredAsJson() {
        TracingService.TracedSpan span = service().start("node.test", TRACE_ID, "",
                1L, 2L, Map.of("nodeKey", "a"));
        span.attribute("status", "OK");
        span.close();

        assertThat(JsonUtils.toJson(span.allAttributes())).contains("nodeKey");
    }

    @Test
    @DisplayName("the trace of the thread is restored when the span ends")
    void theTraceOfTheThreadIsRestored() {
        TracingService tracing = service();
        String before = TraceContext.currentTraceIdOrNull();

        TracingService.TracedSpan span = tracing.start("node.test", TRACE_ID, "", 1L, 2L, Map.of());
        assertThat(TraceContext.currentTraceId()).isEqualTo(TRACE_ID);
        span.close();

        // A span must not leak its trace into the work that follows it, otherwise a message
        // would carry a trace id that belongs to another task.
        assertThat(TraceContext.currentTraceIdOrNull()).isEqualTo(before);
    }
}
