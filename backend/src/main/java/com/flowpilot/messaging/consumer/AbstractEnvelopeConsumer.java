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
 * requeued forever. A failure of the infrastructure is retried a bounded number of times, so a
 * database that restarts does not cost the work.
 */
@Slf4j
public abstract class AbstractEnvelopeConsumer {

    private final MessageDeduplicator deduplicator;
    private final TransactionTemplate transactionTemplate;

    /** The delivery attempts of the messages that are currently being retried. */
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> attempts =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * How often a message is redelivered before it is dead lettered.
     *
     * <p>A temporary failure — the database is restarting — must not cost the work, but a
     * message that fails again and again must not keep the consumer busy for ever.
     */
    @org.springframework.beans.factory.annotation.Value("${flowpilot.messaging.max-redeliveries:3}")
    private int maxRedeliveries;

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
            // The message is done, so it does not have to be counted any more.
            attempts.remove(envelope.getMessageId());
        } catch (Exception ex) {
            log.error("consumer failed consumer={} messageId={} type={}",
                    consumerName(), envelope.getMessageId(), envelope.getMessageType(), ex);
            if (canRetry(envelope, ex)) {
                // A temporary failure — the database is restarting, the connection is lost.
                // The message is put back into the queue, so the work is not lost because of
                // a hiccup. The counter keeps the message from cycling for ever.
                log.warn("consumer will retry consumer={} messageId={} type={}",
                        consumerName(), envelope.getMessageId(), envelope.getMessageType());
                requeue(channel, deliveryTag);
                return;
            }
            // A business failure must not be requeued indefinitely. The dead letter queue keeps
            // the payload for manual inspection and compensation.
            reject(channel, deliveryTag);
            // The message is given up, so it does not have to be counted any more.
            attempts.remove(envelope.getMessageId());
        } finally {
            TraceContext.clear();
        }
    }

    /**
     * Whether the message may be handed to another delivery attempt.
     *
     * <p>A message is only retried if the claim of the message rolled back with the business
     * change. Otherwise the redelivery would be taken for a duplicate and the work would be
     * lost. The node worker does not share the transaction: its recovery is the lease of the
     * node, which reschedules the node with a new message.
     */
    private boolean canRetry(MessageEnvelope<?> envelope, Exception ex) {
        if (!handleInTransaction() || !isRetryable(ex)) {
            return false;
        }
        return countAttempt(envelope.getMessageId()) <= maxRedeliveries;
    }

    /**
     * Counts the delivery attempts of a message.
     *
     * <p>The counter lives in the consumer, so it is lost when the service restarts. That is
     * acceptable: the broker redelivers a message at most a bounded number of times anyway, and
     * a service that restarts often is a service that has bigger problems.
     */
    private int countAttempt(String messageId) {
        return attempts.compute(messageId, (key, count) -> count == null ? 1 : count + 1);
    }

    /**
     * Whether the failure is worth another attempt.
     *
     * <p>Only the failures of the infrastructure are retried. A business failure — a malformed
     * request or a rule that rejects the input — fails again with every attempt.
     */
    private boolean isRetryable(Exception ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof org.springframework.dao.TransientDataAccessException
                    || cause instanceof org.springframework.dao.DataAccessResourceFailureException
                    || cause instanceof org.springframework.dao.CannotAcquireLockException
                    || cause instanceof org.springframework.dao.QueryTimeoutException
                    || cause instanceof java.sql.SQLTransientException
                    || cause instanceof java.net.SocketTimeoutException
                    || cause instanceof java.io.IOException
                    || cause instanceof java.util.concurrent.TimeoutException
                    || cause instanceof org.springframework.amqp.AmqpException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * Puts the message back into the queue.
     */
    private void requeue(Channel channel, long deliveryTag) {
        try {
            channel.basicNack(deliveryTag, false, true);
        } catch (Exception ex) {
            log.error("failed to requeue the message: {}", ex.getMessage());
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
