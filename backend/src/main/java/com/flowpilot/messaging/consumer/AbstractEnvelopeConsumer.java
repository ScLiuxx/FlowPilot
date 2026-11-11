package com.flowpilot.messaging.consumer;

import com.flowpilot.common.trace.TraceContext;
import com.flowpilot.common.util.JsonUtils;
import com.flowpilot.messaging.outbox.MessageDeduplicator;
import com.flowpilot.messaging.protocol.MessageEnvelope;
import com.flowpilot.messaging.protocol.UnsupportedProtocolVersionException;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Base class for the backend consumers.
 *
 * <p>It implements the protocol's consumer template:
 * <ol>
 *   <li>parse and validate the envelope,</li>
 *   <li>quarantine messages with an unsupported protocol version,</li>
 *   <li>claim the message for idempotent processing,</li>
 *   <li>run the business handler,</li>
 *   <li>acknowledge only after the business work succeeded.</li>
 * </ol>
 *
 * <p>Failures that look permanent are rejected into the dead letter queue instead of being
 * requeued forever.
 */
@Slf4j
public abstract class AbstractEnvelopeConsumer {

    private final MessageDeduplicator deduplicator;
    private final TransactionTemplate transactionTemplate;

    protected AbstractEnvelopeConsumer(MessageDeduplicator deduplicator,
                                        TransactionTemplate transactionTemplate) {
        this.deduplicator = deduplicator;
        this.transactionTemplate = transactionTemplate;
    }

    /** Unique name of this consumer, used as part of the dedup key. */
    protected abstract String consumerName();

    /** Handles a validated envelope. */
    protected abstract void handle(MessageEnvelope<?> envelope);

    /**
     * Whether the claim and the business handling must share one transaction.
     *
     * <p>True for the consumers that only touch the database: the claim then rolls back with the
     * business change, so a redelivered message is processed again.
     *
     * <p>False for the node worker: the node execution reaches out to external systems and must
     * not hold a database transaction open while it does. The claim is committed first, and the
     * business idempotency comes from the node lease instead. A worker that dies mid-execution
     * leaves a node whose lease expires, and the recovery scanner reschedules it.
     */
    protected boolean handleInTransaction() {
        return true;
    }

    public void onMessage(Message message, Channel channel) throws Exception {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        MessageEnvelope<?> envelope;
        try {
            String body = new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8);
            envelope = parse(body);
            envelope.validate();
        } catch (UnsupportedProtocolVersionException ex) {
            log.error("quarantining message with unsupported protocol version: {}", ex.getMessage());
            reject(channel, deliveryTag);
            return;
        } catch (Exception ex) {
            log.error("rejecting malformed message: {}", ex.getMessage());
            reject(channel, deliveryTag);
            return;
        }

        TraceContext.setTraceId(envelope.getTraceId());
        try {
            boolean processed = handleInTransaction()
                    ? handleInTransaction(envelope)
                    : handleWithOwnClaimTransaction(envelope);
            if (!processed) {
                log.info("duplicate message ignored consumer={} messageId={}",
                        consumerName(), envelope.getMessageId());
            }
            channel.basicAck(deliveryTag, false);
        } catch (Exception ex) {
            log.error("consumer failed consumer={} messageId={} type={}",
                    consumerName(), envelope.getMessageId(), envelope.getMessageType(), ex);
            // A business failure must not be requeued indefinitely. The dead letter queue keeps
            // the payload for manual inspection and compensation.
            reject(channel, deliveryTag);
        } finally {
            TraceContext.clear();
        }
    }

    /**
     * Claims the message and handles it in one transaction. A failure rolls back both, so the
     * message is not acknowledged and the broker redelivers it.
     *
     * @return false when the message was already processed
     */
    private boolean handleInTransaction(MessageEnvelope<?> envelope) {
        Boolean[] claimed = new Boolean[1];
        transactionTemplate.executeWithoutResult(status -> {
            claimed[0] = deduplicator.tryClaim(consumerName(), envelope.getMessageId());
            if (!claimed[0]) {
                return;
            }
            handle(envelope);
            deduplicator.markConsumed(consumerName(), envelope.getMessageId());
        });
        return Boolean.TRUE.equals(claimed[0]);
    }

    /**
     * Claims the message in its own transaction and handles it afterwards.
     */
    private boolean handleWithOwnClaimTransaction(MessageEnvelope<?> envelope) {
        Boolean claimed = transactionTemplate.execute(status ->
                deduplicator.tryClaim(consumerName(), envelope.getMessageId()));
        if (!Boolean.TRUE.equals(claimed)) {
            return false;
        }
        handle(envelope);
        return true;
    }

    @SuppressWarnings("unchecked")
    protected MessageEnvelope<Object> parse(String body) {
        try {
            return JsonUtils.mapper().readValue(body, MessageEnvelope.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Envelope is not valid JSON", ex);
        }
    }

    /**
     * Rejects the message without requeueing it, so the broker routes it to the dead letter
     * exchange.
     */
    protected void reject(Channel channel, long deliveryTag) {
        try {
            channel.basicReject(deliveryTag, false);
        } catch (Exception ex) {
            log.error("failed to reject message to dead letter queue: {}", ex.getMessage());
        }
    }
}
