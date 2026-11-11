package com.flowpilot.messaging.outbox;

import com.flowpilot.common.util.JsonUtils;
import com.flowpilot.messaging.outbox.entity.MessageOutbox;
import com.flowpilot.messaging.outbox.mapper.MessageOutboxMapper;
import com.flowpilot.messaging.protocol.MessageEnvelope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Forwards outbox rows to RabbitMQ.
 *
 * <p>The publisher never deletes a row. It only marks it as published after the broker confirmed
 * the message. If the publisher crashes between sending and updating, the row is picked up again
 * and the message is delivered twice. That is acceptable: consumers are idempotent, which is the
 * documented delivery semantics of the platform.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private final MessageOutboxMapper outboxMapper;
    private final RabbitTemplate rabbitTemplate;

    @Value("${flowpilot.messaging.outbox.batch-size:100}")
    private int batchSize;

    @Value("${flowpilot.messaging.outbox.enabled:true}")
    private boolean enabled;

    @Value("${flowpilot.messaging.outbox.confirm-timeout-ms:10000}")
    private long confirmTimeoutMs;

    @Scheduled(fixedDelayString = "${flowpilot.messaging.outbox.poll-interval-ms:500}")
    public void publishPending() {
        if (!enabled) {
            return;
        }
        List<MessageOutbox> batch = claimBatch();
        for (MessageOutbox record : batch) {
            publishOne(record);
        }
    }

    /**
     * Claims rows with a short transaction, then publishes them outside of any transaction so the
     * database connection is not held while waiting for the broker.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<MessageOutbox> claimBatch() {
        List<MessageOutbox> candidates = outboxMapper.claimPending(LocalDateTime.now(), batchSize);
        return candidates.stream()
                .filter(record -> outboxMapper.markPublishing(record.getId()) == 1)
                .toList();
    }

    private void publishOne(MessageOutbox record) {
        try {
            MessageEnvelope<?> envelope = JsonUtils.fromJson(record.getPayload(), MessageEnvelope.class);

            // The correlation data carries the future of the publisher confirm, so the row can
            // only be marked as published once the broker actually took the message.
            CorrelationData correlationData = new CorrelationData(record.getMessageId());
            rabbitTemplate.convertAndSend(record.getRoutingKey(), envelope, message -> {
                message.getMessageProperties().setMessageId(record.getMessageId());
                message.getMessageProperties().setHeader("x-message-type", record.getMessageType());
                message.getMessageProperties().setDeliveryMode(
                        org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT);
                return message;
            }, correlationData);

            CorrelationData.Confirm confirm = correlationData.getFuture()
                    .get(confirmTimeoutMs, TimeUnit.MILLISECONDS);
            if (!confirm.isAck()) {
                throw new IllegalStateException("the broker rejected the message: " + confirm.getReason());
            }

            outboxMapper.markPublished(record.getId());
            log.debug("outbox published id={} type={} routingKey={}",
                    record.getId(), record.getMessageType(), record.getRoutingKey());
        } catch (Exception ex) {
            log.warn("outbox publish failed id={} routingKey={}: {}", record.getId(),
                    record.getRoutingKey(), ex.getMessage());
            reschedule(record);
        }
    }

    private void reschedule(MessageOutbox record) {
        int retry = record.getRetryCount() == null ? 0 : record.getRetryCount();
        long delaySeconds = Math.min(60, 1L << Math.min(retry, 6));
        outboxMapper.reschedule(record.getId(), LocalDateTime.now().plusSeconds(delaySeconds));
    }

    /**
     * Recovers rows that were claimed for publishing but never confirmed, which happens when the
     * publisher dies mid-flight.
     */
    @Scheduled(fixedDelayString = "${flowpilot.messaging.outbox.stale-scan-interval-ms:60000}")
    @Transactional
    public void recoverStale() {
        if (!enabled) {
            return;
        }
        List<MessageOutbox> stale = outboxMapper.findStalePublishing(
                LocalDateTime.now().minusMinutes(5), batchSize);
        for (MessageOutbox record : stale) {
            outboxMapper.reschedule(record.getId(), LocalDateTime.now());
            log.warn("recovered stale outbox row id={} messageId={}", record.getId(), record.getMessageId());
        }
    }
}
