package com.flowpilot.messaging.consumer;

import com.flowpilot.agent.service.AgentEventHandler;
import com.flowpilot.common.util.JsonUtils;
import com.flowpilot.messaging.config.RabbitTopology;
import com.flowpilot.messaging.outbox.MessageDeduplicator;
import com.flowpilot.messaging.protocol.MessageEnvelope;
import com.flowpilot.messaging.protocol.MessageType;
import com.flowpilot.messaging.protocol.payloads.AgentCompletedPayload;
import com.flowpilot.messaging.protocol.payloads.AgentFailedPayload;
import com.flowpilot.messaging.protocol.payloads.AgentInterruptedPayload;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Consumes the result events the agent service publishes.
 */
@Slf4j
@Component
public class AgentEventConsumer extends AbstractEnvelopeConsumer {

    private final AgentEventHandler eventHandler;

    public AgentEventConsumer(MessageDeduplicator deduplicator, TransactionTemplate transactionTemplate,
                              AgentEventHandler eventHandler) {
        super(deduplicator, transactionTemplate);
        this.eventHandler = eventHandler;
    }

    @Override
    protected String consumerName() {
        return "backend-agent-events";
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_BACKEND_AGENT_EVENTS, concurrency = "1-4")
    public void listen(Message message, Channel channel) throws Exception {
        onMessage(message, channel);
    }

    @Override
    protected void handle(MessageEnvelope<?> envelope) {
        MessageType type;
        try {
            type = MessageType.fromWireName(envelope.getMessageType());
        } catch (IllegalArgumentException ex) {
            log.warn("ignoring unknown message type {}", envelope.getMessageType());
            return;
        }
        switch (type) {
            case AGENT_COMPLETED -> eventHandler.onCompleted(JsonUtils.mapper()
                    .convertValue(envelope.getPayload(), AgentCompletedPayload.class));
            case AGENT_FAILED -> eventHandler.onFailed(JsonUtils.mapper()
                    .convertValue(envelope.getPayload(), AgentFailedPayload.class));
            case AGENT_INTERRUPTED -> eventHandler.onInterrupted(JsonUtils.mapper()
                    .convertValue(envelope.getPayload(), AgentInterruptedPayload.class));
            default -> log.debug("message type {} is not an agent result event", type);
        }
    }
}
