package com.flowpilot.execution;

import com.flowpilot.execution.entity.ExecutionEvent;
import com.flowpilot.execution.entity.WorkflowExecution;
import com.flowpilot.execution.mapper.NodeExecutionMapper;
import com.flowpilot.execution.model.EventType;
import com.flowpilot.execution.recovery.DataRetentionScheduler;
import com.flowpilot.execution.scheduler.WorkflowScheduler;
import com.flowpilot.execution.service.ExecutionEventService;
import com.flowpilot.execution.service.WorkflowExecutionService;
import com.flowpilot.messaging.outbox.mapper.MessageOutboxMapper;
import com.flowpilot.workflow.definition.WorkflowDefinitionDocument;
import com.flowpilot.workflow.definition.WorkflowNodeDefinition;
import com.flowpilot.workflow.entity.WorkflowVersion;
import com.flowpilot.workflow.service.WorkflowService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests of the data lifecycle.
 *
 * <p>The database design lists the retention rules: the execution events are archived, the
 * outbox rows are cleaned up after they were sent, and the deduplication records are kept long
 * enough that a message cannot be delivered again after its record was deleted.
 */
@SpringBootTest
@ActiveProfiles("test")
class DataRetentionIntegrationTest {

    @Autowired
    private WorkflowService workflowService;
    @Autowired
    private WorkflowExecutionService executionService;
    @Autowired
    private WorkflowScheduler scheduler;
    @Autowired
    private NodeExecutionMapper nodeExecutionMapper;
    @Autowired
    private MessageOutboxMapper outboxMapper;
    @Autowired
    private ExecutionEventService eventService;
    @Autowired
    private DataRetentionScheduler retentionScheduler;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("the old events are moved into the archive table")
    void oldEventsAreArchived() {
        Long executionId = startWorkflow(9601L, "archive demo");

        int before = countEvents(executionId);
        assertThat(before).isGreaterThan(0);

        // The events are moved into the past.
        jdbcTemplate.update("UPDATE execution_event SET created_at = ? WHERE workflow_execution_id = ?",
                LocalDateTime.now().minusDays(60), executionId);

        retentionScheduler.archiveEvents();

        assertThat(countEvents(executionId)).isZero();
        Integer archived = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM execution_event_archive WHERE workflow_execution_id = ?",
                Integer.class, executionId);
        assertThat(archived).isEqualTo(before);
    }

    @Test
    @DisplayName("the events of a finished execution are still readable from the archive")
    void archivedEventsAreStillReadable() {
        Long executionId = startWorkflow(9602L, "archive read demo");

        jdbcTemplate.update("UPDATE execution_event SET created_at = ? WHERE workflow_execution_id = ?",
                LocalDateTime.now().minusDays(60), executionId);
        retentionScheduler.archiveEvents();

        // The console asks for the whole chain, so the archive has to answer as well.
        List<ExecutionEvent> events = retentionScheduler.listAllWithArchive(executionId, 100);
        assertThat(events).isNotEmpty();
        assertThat(events).extracting(ExecutionEvent::getEventType)
                .contains(EventType.WORKFLOW_CREATED.name());
    }

    @Test
    @DisplayName("the published outbox rows are deleted, the pending ones are kept")
    void publishedOutboxRowsAreDeleted() {
        Long executionId = startWorkflow(9603L, "outbox cleanup demo");

        // The message of the node was sent, the message of the workflow is still pending.
        jdbcTemplate.update("UPDATE message_outbox SET status = 'PUBLISHED', published_at = ? "
                + "WHERE aggregate_id IN (SELECT id FROM node_execution WHERE workflow_execution_id = ?)",
                LocalDateTime.now().minusDays(30), executionId);

        retentionScheduler.deletePublishedOutbox();

        Integer published = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM message_outbox WHERE status = 'PUBLISHED' AND aggregate_id IN "
                        + "(SELECT id FROM node_execution WHERE workflow_execution_id = ?)",
                Integer.class, executionId);
        assertThat(published).isZero();

        // The message of the workflow was never sent, so it has to survive.
        Integer pending = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM message_outbox WHERE status = 'PENDING' AND aggregate_id = ?",
                Integer.class, executionId);
        assertThat(pending).isGreaterThan(0);
    }

    @Test
    @DisplayName("the deduplication records of the consumed messages are deleted")
    void consumedRecordsAreDeleted() {
        // A record that was never consumed has to survive, because the message can still be
        // delivered again.
        jdbcTemplate.update("INSERT INTO message_consume_record "
                + "(id, consumer_name, message_id, status, created_at) VALUES (?, ?, ?, 'PROCESSING', ?)",
                999000000001L, "test-consumer", "message-pending", LocalDateTime.now());
        // A record of a consumed message can go.
        jdbcTemplate.update("INSERT INTO message_consume_record "
                + "(id, consumer_name, message_id, status, consumed_at, created_at) "
                + "VALUES (?, ?, ?, 'CONSUMED', ?, ?)",
                999000000002L, "test-consumer", "message-consumed",
                LocalDateTime.now().minusDays(30), LocalDateTime.now().minusDays(30));

        retentionScheduler.deleteConsumedRecords();

        Integer pending = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM message_consume_record WHERE message_id = 'message-pending'",
                Integer.class);
        assertThat(pending).isEqualTo(1);

        Integer consumed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM message_consume_record WHERE message_id = 'message-consumed'",
                Integer.class);
        assertThat(consumed).isZero();

        jdbcTemplate.update("DELETE FROM message_consume_record WHERE consumer_name = 'test-consumer'");
    }

    private int countEvents(Long executionId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM execution_event WHERE workflow_execution_id = ?",
                Integer.class, executionId);
    }

    private Long startWorkflow(Long userId, String name) {
        WorkflowDefinitionDocument document = WorkflowDefinitionDocument.builder()
                .name("test")
                .nodes(List.of(WorkflowNodeDefinition.builder()
                        .id("only").type("CODE")
                        .config(Map.of("operation", "identity"))
                        .dependencies(List.of())
                        .build()))
                .build();

        var workflow = workflowService.create(name, null, "TEST", userId, document);
        var version = workflowService.listVersionsInternal(workflow.getId()).get(0);
        workflowService.publish(workflow.getId(), version.getId(), userId);
        WorkflowVersion published = workflowService.getPublishedVersion(workflow.getId());

        WorkflowExecution execution = executionService.start(published, document, userId, Map.of());
        scheduler.onWorkflowStarted(execution.getId());
        return execution.getId();
    }
}
