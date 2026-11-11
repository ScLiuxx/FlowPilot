package com.flowpilot.messaging.outbox;

import com.flowpilot.common.id.SnowflakeIdGenerator;
import com.flowpilot.messaging.outbox.entity.MessageConsumeRecord;
import com.flowpilot.messaging.outbox.mapper.MessageConsumeRecordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Message level deduplication.
 *
 * <p>The unique constraint on {@code (consumer_name, message_id)} makes the check atomic: two
 * concurrent deliveries of the same message race for the insert and only one of them wins. The
 * loser treats the message as a duplicate and acknowledges it.
 *
 * <p>The claim must run in the same transaction as the business handling. If the business change
 * fails, the claim rolls back with it and the redelivered message is processed again.
 *
 * <p>This only protects against the same message being delivered twice. It does not replace the
 * business level idempotency, which is enforced by the conditional state transitions on the
 * execution tables.
 */
@Service
@RequiredArgsConstructor
public class MessageDeduplicator {

    private final MessageConsumeRecordMapper consumeRecordMapper;
    private final SnowflakeIdGenerator idGenerator;

    /**
     * Tries to claim a message for processing.
     *
     * @return true when this caller is the first to process the message
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean tryClaim(String consumerName, String messageId) {
        MessageConsumeRecord existing = consumeRecordMapper.find(consumerName, messageId);
        if (existing != null) {
            return false;
        }
        MessageConsumeRecord record = MessageConsumeRecord.builder()
                .id(idGenerator.nextId())
                .consumerName(consumerName)
                .messageId(messageId)
                .status(MessageConsumeRecord.STATUS_PROCESSING)
                .createdAt(LocalDateTime.now())
                .build();
        try {
            consumeRecordMapper.insert(record);
            return true;
        } catch (DuplicateKeyException ex) {
            // Another consumer instance claimed the same message concurrently.
            return false;
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void markConsumed(String consumerName, String messageId) {
        consumeRecordMapper.markConsumed(consumerName, messageId);
    }
}
