package com.flowpilot.execution.recovery;

import com.flowpilot.execution.entity.ExecutionEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Archives the old events and cleans up the tables that grow without a bound.
 *
 * <p>The database design lists the retention rules of the platform: the execution events are
 * archived regularly, the outbox rows are cleaned up with a delay after they were sent, and the
 * deduplication records are kept long enough that a message cannot be delivered again after its
 * record was deleted.
 *
 * <p>The deduplication records are the reason the cleanup is careful. A record that is deleted
 * while the broker can still redeliver the message would let the message be processed twice.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataRetentionScheduler {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    @Value("${flowpilot.retention.enabled:true}")
    private boolean enabled;

    /** How long the events of a finished execution are kept before they are archived. */
    @Value("${flowpilot.retention.event-days:30}")
    private int eventDays;

    /** How long a published outbox row is kept. */
    @Value("${flowpilot.retention.outbox-days:7}")
    private int outboxDays;

    /**
     * How long a consumed deduplication record is kept.
     *
     * <p>The value has to be longer than the longest redelivery window of the broker, otherwise
     * a redelivered message would be processed a second time.
     */
    @Value("${flowpilot.retention.consume-record-days:7}")
    private int consumeRecordDays;

    @Scheduled(fixedDelayString = "${flowpilot.retention.interval-ms:3600000}")
    public void cleanUp() {
        if (!enabled) {
            return;
        }
        archiveEvents();
        deletePublishedOutbox();
        deleteConsumedRecords();
    }

    /**
     * Moves the events of the old executions into the archive table.
     */
    public void archiveEvents() {
        LocalDateTime deadline = LocalDateTime.now().minusDays(eventDays);
        Integer moved = transactionTemplate.execute(status -> {
            int count = jdbcTemplate.update("""
                    INSERT INTO execution_event_archive
                        (id, workflow_execution_id, node_execution_id, event_type, event_data,
                         sequence_no, operator_id, trace_id, created_at)
                    SELECT id, workflow_execution_id, node_execution_id, event_type, event_data,
                           sequence_no, operator_id, trace_id, created_at
                      FROM execution_event
                     WHERE created_at < ?
                    """, deadline);
            if (count > 0) {
                jdbcTemplate.update("DELETE FROM execution_event WHERE created_at < ?", deadline);
            }
            return count;
        });
        if (moved != null && moved > 0) {
            log.info("archived {} execution events that are older than {} days", moved, eventDays);
        }
    }

    /**
     * Deletes the outbox rows that were sent.
     */
    public void deletePublishedOutbox() {
        LocalDateTime deadline = LocalDateTime.now().minusDays(outboxDays);
        int deleted = jdbcTemplate.update("""
                DELETE FROM message_outbox
                 WHERE status = 'PUBLISHED'
                   AND published_at IS NOT NULL
                   AND published_at < ?
                """, deadline);
        if (deleted > 0) {
            log.info("deleted {} published outbox rows", deleted);
        }
    }

    /**
     * Deletes the deduplication records of the messages that were consumed.
     */
    public void deleteConsumedRecords() {
        LocalDateTime deadline = LocalDateTime.now().minusDays(consumeRecordDays);
        int deleted = jdbcTemplate.update("""
                DELETE FROM message_consume_record
                 WHERE status = 'CONSUMED'
                   AND consumed_at IS NOT NULL
                   AND consumed_at < ?
                """, deadline);
        if (deleted > 0) {
            log.info("deleted {} consumed deduplication records", deleted);
        }
    }

    /**
     * The events of an execution, including the archived ones.
     *
     * <p>The console asks for the whole chain of an execution. When the events were already
     * archived, the answer contains them as well, so the chain is complete.
     */
    public List<ExecutionEvent> listAllWithArchive(Long executionId, int limit) {
        List<ExecutionEvent> events = jdbcTemplate.query("""
                SELECT * FROM execution_event
                 WHERE workflow_execution_id = ?
                 ORDER BY sequence_no
                 LIMIT ?
                """, (rs, rowNum) -> {
            ExecutionEvent event = new ExecutionEvent();
            event.setId(rs.getLong("id"));
            event.setWorkflowExecutionId(rs.getLong("workflow_execution_id"));
            long nodeExecutionId = rs.getLong("node_execution_id");
            event.setNodeExecutionId(rs.wasNull() ? null : nodeExecutionId);
            event.setEventType(rs.getString("event_type"));
            event.setEventData(rs.getString("event_data"));
            event.setSequenceNo(rs.getLong("sequence_no"));
            long operatorId = rs.getLong("operator_id");
            event.setOperatorId(rs.wasNull() ? null : operatorId);
            event.setTraceId(rs.getString("trace_id"));
            event.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
            return event;
        }, executionId, limit);

        if (events.size() >= limit) {
            return events;
        }
        List<ExecutionEvent> archived = jdbcTemplate.query("""
                SELECT * FROM execution_event_archive
                 WHERE workflow_execution_id = ?
                 ORDER BY sequence_no
                 LIMIT ?
                """, (rs, rowNum) -> {
            ExecutionEvent event = new ExecutionEvent();
            event.setId(rs.getLong("id"));
            event.setWorkflowExecutionId(rs.getLong("workflow_execution_id"));
            long nodeExecutionId = rs.getLong("node_execution_id");
            event.setNodeExecutionId(rs.wasNull() ? null : nodeExecutionId);
            event.setEventType(rs.getString("event_type"));
            event.setEventData(rs.getString("event_data"));
            event.setSequenceNo(rs.getLong("sequence_no"));
            event.setTraceId(rs.getString("trace_id"));
            event.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
            return event;
        }, executionId, limit - events.size());
        List<ExecutionEvent> merged = new java.util.ArrayList<>(archived);
        merged.addAll(events);
        merged.sort(java.util.Comparator.comparingLong(ExecutionEvent::getSequenceNo));
        return merged;
    }
}
