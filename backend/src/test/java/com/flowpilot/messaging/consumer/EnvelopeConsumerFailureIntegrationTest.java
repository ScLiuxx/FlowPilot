package com.flowpilot.messaging.consumer;

import com.flowpilot.messaging.outbox.MessageDeduplicator;
import com.flowpilot.messaging.protocol.MessageEnvelope;
import com.flowpilot.messaging.protocol.MessageType;
import com.flowpilot.messaging.protocol.payloads.NodeCompletedPayload;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests of the failure handling of the consumers.
 *
 * <p>A database that restarts must not cost the work: the message is put back into the queue
 * and processed again. A business failure fails again with every attempt, so it is put into the
 * dead letter queue.
 */
@SpringBootTest
@ActiveProfiles("test")
class EnvelopeConsumerFailureIntegrationTest {

    @Autowired
    private MessageDeduplicator deduplicator;

    @Autowired
    private TransactionTemplate transactionTemplate;

    /** A consumer whose work always fails with the given error. */
    private static class FailingConsumer extends AbstractEnvelopeConsumer {

        private final Exception error;

        FailingConsumer(MessageDeduplicator deduplicator, TransactionTemplate transactionTemplate,
                        Exception error) {
            super(deduplicator, transactionTemplate);
            this.error = error;
        }

        @Override
        protected String consumerName() {
            return "failing-test-consumer";
        }

        @Override
        protected void handle(MessageEnvelope<?> envelope) {
            // The error is wrapped, so the type of the cause still decides whether the
            // failure is worth another attempt.
            throw new RuntimeException(error);
        }
    }

    /**
     * A channel that only records what the consumer did with the message.
     *
     * <p>The channel of the broker is a class, so it is recorded with a proxy: only the three
     * methods the consumer uses are implemented.
     */
    private static class RecordingChannel {

        int acks;
        int rejects;
        int requeues;

        Channel channel() {
            return (Channel) java.lang.reflect.Proxy.newProxyInstance(
                    Channel.class.getClassLoader(),
                    new Class<?>[]{Channel.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "basicAck" -> acks++;
                            case "basicReject" -> rejects++;
                            case "basicNack" -> requeues++;
                            default -> {
                            }
                        }
                        return null;
                    });
        }
    }

    @Test
    @DisplayName("a temporary failure is put back into the queue")
    void aTemporaryFailureIsRequeued() throws Exception {
        FailingConsumer consumer = consumerWith(
                new org.springframework.dao.QueryTimeoutException("the database is busy"));
        RecordingChannel recording = new RecordingChannel();

        consumer.onMessage(message(), recording.channel());

        // The message is not lost: it goes back into the queue.
        assertThat(recording.requeues).isEqualTo(1);
        assertThat(recording.acks).isZero();
        assertThat(recording.rejects).isZero();
    }

    @Test
    @DisplayName("a business failure is put into the dead letter queue")
    void aBusinessFailureIsDeadLettered() throws Exception {
        FailingConsumer consumer = consumerWith(
                new IllegalStateException("the payload is wrong"));
        RecordingChannel recording = new RecordingChannel();

        consumer.onMessage(message(), recording.channel());

        assertThat(recording.rejects).isEqualTo(1);
        assertThat(recording.acks).isZero();
        assertThat(recording.requeues).isZero();
    }

    @Test
    @DisplayName("a message that keeps failing is given up after a few attempts")
    void aMessageThatKeepsFailingIsGivenUp() throws Exception {
        FailingConsumer consumer = consumerWith(
                new org.springframework.dao.QueryTimeoutException("the database is busy"));
        RecordingChannel recording = new RecordingChannel();

        // The consumer retries a bounded number of times, so a message that cannot be handled
        // does not keep the consumer busy for ever: the fourth delivery is given up.
        for (int delivery = 0; delivery < 4; delivery++) {
            consumer.onMessage(message(), recording.channel());
        }

        assertThat(recording.requeues).isEqualTo(3);
        assertThat(recording.rejects).isEqualTo(1);
    }

    /**
     * A consumer of the test, with the configured limit of the redeliveries.
     */
    private FailingConsumer consumerWith(Exception error) {
        FailingConsumer consumer = new FailingConsumer(deduplicator, transactionTemplate, error);
        // The consumer is built by hand, so the configured limit is set explicitly.
        ReflectionTestUtils.setField(consumer, "maxRedeliveries", 3);
        return consumer;
    }

    /**
     * A message of the protocol, as the broker delivers it.
     *
     * <p>The message always has the same id: it is the same message that the broker
     * redelivers, so the counter of the attempts counts it up.
     */
    private Message message() {
        MessageEnvelope<NodeCompletedPayload> envelope = MessageEnvelope.create(
                MessageType.NODE_COMPLETED,
                NodeCompletedPayload.builder()
                        .nodeKey("collect_repository")
                        .result(Map.of("stars", 12000))
                        .build());
        envelope.setMessageId("failing-test-message");
        envelope.setWorkflowExecutionId(10001L);
        envelope.setNodeExecutionId(20001L);
        envelope.setAttemptNo(1);

        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(1L);
        byte[] body = com.flowpilot.common.util.JsonUtils.toJson(envelope)
                .getBytes(StandardCharsets.UTF_8);
        return new Message(body, properties);
    }
}
