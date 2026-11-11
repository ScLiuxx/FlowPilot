package com.flowpilot.messaging.outbox;

import com.flowpilot.common.id.SnowflakeIdGenerator;
import com.flowpilot.common.util.JsonUtils;
import com.flowpilot.messaging.outbox.entity.MessageOutbox;
import com.flowpilot.messaging.outbox.mapper.MessageOutboxMapper;
import com.flowpilot.messaging.protocol.MessageEnvelope;
import com.flowpilot.messaging.protocol.MessageType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Writes outgoing messages into the transactional outbox.
 *
 * <p>The insert always joins the caller's transaction, so the business state change and the
 * intent to publish a message commit together. A separate publisher later forwards the rows to
 * RabbitMQ.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxService {

    private final MessageOutboxMapper outboxMapper;
    private final SnowflakeIdGenerator idGenerator;

    /**
     * Persists a message for later delivery.
     *
     * @param aggregateType business aggregate the message belongs to, e.g. {@code WORKFLOW}
     * @param aggregateId   id of that aggregate
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(MessageType type,
                        String aggregateType,
                        Long aggregateId,
                        MessageEnvelope<?> envelope) {
        envelope.validate();
        MessageOutbox record = MessageOutbox.builder()
                .id(idGenerator.nextId())
                .messageId(envelope.getMessageId())
                .messageType(envelope.getMessageType())
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .routingKey(type.routingKey())
                .payload(JsonUtils.toJson(envelope))
                .status(MessageOutbox.STATUS_PENDING)
                .retryCount(0)
                .createdAt(LocalDateTime.now())
                .build();
        outboxMapper.insert(record);
        log.debug("outbox enqueued type={} aggregate={}:{} messageId={}",
                type, aggregateType, aggregateId, envelope.getMessageId());
    }
}
