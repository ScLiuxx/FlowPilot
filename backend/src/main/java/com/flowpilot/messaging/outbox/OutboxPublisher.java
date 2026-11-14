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
 *
 * <p>The claim and the recovery of the stale rows live in {@link OutboxClaimService}: a
 * {@code @Transactional} method only gets a transaction when it is called through the Spring
 * proxy, so a self invocation would silently run without one.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private final OutboxClaimService claimService;
    private final MessageOutboxMapper outboxMapper;
    private final RabbitTemplate rabbitTemplate;

    @Value("${flowpilot.messaging.outbox.batch-size:100}")
    private int batchSize;

    @Value("${flowpilot.messaging.outbox.enabled:true}")
    private boolean enabled;

    @Value("${flowpilot.messaging.outbox.confirm-timeout-ms:10000}")
    private long confirmTimeoutMs;

    /**
     * How long a row may stay in the publishing state before it is recovered.
     */
    @Value("${flowpilot.messaging.outbox.publishing-timeout-seconds:300}")
    private long publishingTimeoutSeconds;

    /**
     * How often a message is published before it is given up.
     *
     * <p>Most failures are temporary: the broker is restarting or the connection is lost. A
     * message that cannot be published at all — a malformed payload or a routing key without a
     * queue — would be retried for ever without this limit.
     */
    @Value("${flowpilot.messaging.outbox.max-retries:10}")
    private int maxRetries;

    @Scheduled(fixedDelayString = "${flowpilot.messaging.outbox.poll-interval-ms:500}")
    public void publishPending() {
        if (!enabled) {
            return;
        }
        List<MessageOutbox> batch = claimService.claimBatch(LocalDateTime.now(), batchSize);
        for (MessageOutbox record : batch) {
            publishOne(record);
        }
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
            // The broker accepted the message but could not route it. The row must not be
            // marked as published, because nobody will ever receive the message.
            if (correlationData.getReturned() != null) {
                throw new IllegalStateException("the message was returned: it has no matching queue");
            }

            // The update is conditional on the status: if the row was recovered in the
            // meantime, the recovery must not be overwritten.
            if (outboxMapper.markPublished(record.getId()) == 0) {
                log.warn("the outbox row {} was recovered while it was published", record.getId());
            }
            log.debug("outbox published id={} type={} routingKey={}",
                    record.getId(), record.getMessageType(), record.getRoutingKey());
        } catch (Exception ex) {
            log.warn("outbox publish failed id={} routingKey={}: {}", record.getId(),
                    record.getRoutingKey(), ex.getMessage());
            reschedule(record);
        }
    }

    /**
     * Returns a row to the pending state, or gives up on it.
     *
     * <p>A message that cannot be published is retried a bounded number of times. Without the
     * limit a single poison message would be retried for ever and would keep the publisher
     * busy.
     */
    private void reschedule(MessageOutbox record) {
        int retry = record.getRetryCount() == null ? 0 : record.getRetryCount();
        if (retry >= maxRetries) {
            outboxMapper.markFailed(record.getId());
            log.error("the outbox row {} failed after {} attempts, it is given up id={} routingKey={}",
                    record.getId(), retry, record.getId(), record.getRoutingKey());
            return;
        }
        long delaySeconds = Math.min(60, 1L << Math.min(retry, 6));
        outboxMapper.reschedule(record.getId(), LocalDateTime.now().plusSeconds(delaySeconds));
    }

    /**
     * Recovers rows that were claimed for publishing but never confirmed, which happens when the
     * publisher dies mid-flight.
     */
    @Scheduled(fixedDelayString = "${flowpilot.messaging.outbox.stale-scan-interval-ms:60000}")
    public void recoverStale() {
        if (!enabled) {
            return;
        }
        claimService.recoverStale(
                LocalDateTime.now().minusSeconds(publishingTimeoutSeconds), batchSize);
    }
}
