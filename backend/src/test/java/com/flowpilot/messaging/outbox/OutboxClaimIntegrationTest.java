package com.flowpilot.messaging.outbox;

import com.flowpilot.messaging.outbox.entity.MessageOutbox;
import com.flowpilot.messaging.outbox.mapper.MessageOutboxMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests of the claim of the outbox rows.
 *
 * <p>The rows are sorted by the moment of their creation. The sort must not carry the payload of
 * the messages through the sort buffer of the database: a message with a payload of a few hundred
 * kilobytes does not fit into the default buffer of two hundred and fifty six kilobytes and the
 * publisher fails with "out of sort memory".
 */
@SpringBootTest
@ActiveProfiles("test")
class OutboxClaimIntegrationTest {

    /** The size of the default sort buffer of the database. */
    private static final int SORT_BUFFER_SIZE = 262144;

    @Autowired
    private OutboxClaimService claimService;
    @Autowired
    private MessageOutboxMapper outboxMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("a message with a payload bigger than the sort buffer is claimed")
    void aBigMessageIsClaimed() {
        long messageId = insertPendingMessage("big-payload", SORT_BUFFER_SIZE * 2);

        // The row is the oldest one, so the claim with a single row takes it and leaves the
        // messages of the other tests alone.
        List<MessageOutbox> claimed = claimService.claimBatch(LocalDateTime.now(), 1);

        assertThat(claimed).extracting(MessageOutbox::getMessageId).contains("big-payload");
        assertThat(statusOf(messageId)).isEqualTo("PUBLISHING");

        cleanUp(messageId);
    }

    @Test
    @DisplayName("a big message that was claimed is recovered when the publisher died")
    void aBigStaleMessageIsRecovered() {
        long messageId = insertPendingMessage("big-stale", SORT_BUFFER_SIZE * 2);
        jdbcTemplate.update("UPDATE message_outbox SET status = 'PUBLISHING', publishing_at = ? WHERE id = ?",
                LocalDateTime.of(2000, 1, 1, 0, 0), messageId);

        List<MessageOutbox> stale = claimService.recoverStale(LocalDateTime.now().minusMinutes(5), 1);

        assertThat(stale).extracting(MessageOutbox::getMessageId).contains("big-stale");
        assertThat(statusOf(messageId)).isEqualTo("PENDING");

        cleanUp(messageId);
    }

    private long insertPendingMessage(String messageId, int payloadSize) {
        // A row of an earlier run must not disturb the test.
        jdbcTemplate.update("DELETE FROM message_outbox WHERE message_id = ?", messageId);
        String payload = "{\"body\":\"" + "x".repeat(payloadSize) + "\"}";
        jdbcTemplate.update("INSERT INTO message_outbox "
                        + "(id, message_id, message_type, aggregate_type, aggregate_id, routing_key, payload, status, created_at) "
                        + "VALUES (?, ?, 'NodeExecute', 'NODE', ?, 'node.execute', ?, 'PENDING', ?)",
                System.nanoTime(), messageId, 999000000000L, payload, LocalDateTime.of(2000, 1, 1, 0, 0));
        Long id = jdbcTemplate.queryForObject("SELECT id FROM message_outbox WHERE message_id = ?",
                Long.class, messageId);
        return id;
    }

    private String statusOf(long id) {
        return jdbcTemplate.queryForObject("SELECT status FROM message_outbox WHERE id = ?", String.class, id);
    }

    private void cleanUp(long id) {
        jdbcTemplate.update("DELETE FROM message_outbox WHERE id = ?", id);
    }
}
