package com.flowpilot.messaging;

import com.flowpilot.messaging.outbox.MessageDeduplicator;
import com.flowpilot.messaging.outbox.mapper.MessageConsumeRecordMapper;
import com.flowpilot.messaging.protocol.MessageEnvelope;
import com.flowpilot.messaging.protocol.MessageType;
import com.flowpilot.messaging.protocol.payloads.NodeCompletedPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests of the consumer side deduplication.
 *
 * <p>RabbitMQ delivers at least once, so the same message can arrive more than once. The
 * deduplication record makes the second delivery a no-op, and because the record shares the
 * transaction with the business handling, a failed handling is rolled back with it.
 */
@SpringBootTest
@ActiveProfiles("test")
class MessageDeduplicationIntegrationTest {

    @Autowired
    private MessageDeduplicator deduplicator;

    @Autowired
    private MessageConsumeRecordMapper consumeRecordMapper;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("T06: the second delivery of a message is not claimed again")
    void secondDeliveryIsNotClaimed() {
        String messageId = "msg-" + System.nanoTime();

        boolean first = transactionTemplate.execute(status ->
                deduplicator.tryClaim("test-consumer", messageId));
        boolean second = transactionTemplate.execute(status ->
                deduplicator.tryClaim("test-consumer", messageId));

        assertThat(first).isTrue();
        assertThat(second).isFalse();
    }

    @Test
    @DisplayName("T06: different consumers may process the same message")
    void differentConsumersMayProcessTheSameMessage() {
        String messageId = "msg-" + System.nanoTime();

        boolean first = transactionTemplate.execute(status ->
                deduplicator.tryClaim("consumer-a", messageId));
        boolean second = transactionTemplate.execute(status ->
                deduplicator.tryClaim("consumer-b", messageId));

        assertThat(first).isTrue();
        assertThat(second).isTrue();
    }

    @Test
    @DisplayName("T06: a rolled back handling releases the claim, so the message is processed again")
    void rolledBackHandlingReleasesTheClaim() {
        String messageId = "msg-" + System.nanoTime();

        // The claim and the business handling share one transaction. When the handling fails, the
        // whole transaction rolls back, including the claim.
        assertThatThrownByRollback(() -> transactionTemplate.executeWithoutResult(status -> {
            deduplicator.tryClaim("test-consumer", messageId);
            throw new IllegalStateException("the business handling failed");
        }));

        boolean afterRollback = transactionTemplate.execute(status ->
                deduplicator.tryClaim("test-consumer", messageId));
        assertThat(afterRollback)
                .as("the message must be processable again after a rollback")
                .isTrue();
    }

    @Test
    @DisplayName("T06: the envelope carries the identifiers the idempotency depends on")
    void envelopeCarriesTheIdempotencyIdentifiers() {
        MessageEnvelope<NodeCompletedPayload> envelope = MessageEnvelope.create(
                MessageType.NODE_COMPLETED,
                NodeCompletedPayload.builder()
                        .nodeKey("collect_repository")
                        .result(Map.of("stars", 12000))
                        .build());
        envelope.setWorkflowExecutionId(10001L);
        envelope.setNodeExecutionId(20001L);
        envelope.setAttemptNo(1);

        assertThat(envelope.getNodeExecutionId()).isNotNull();
        assertThat(envelope.getAttemptNo()).isEqualTo(1);
        assertThat(envelope.getMessageId()).isNotBlank();
        assertThat(envelope.getCorrelationId()).isNotBlank();
    }

    private void assertThatThrownByRollback(Runnable action) {
        try {
            action.run();
        } catch (Exception ex) {
            // The transaction template rolls the transaction back and rethrows.
            return;
        }
        throw new AssertionError("the transaction should have been rolled back");
    }
}
