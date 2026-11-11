package com.flowpilot.messaging.consumer;

import com.flowpilot.execution.scheduler.NodeFailureHandler;
import com.flowpilot.execution.scheduler.WorkflowScheduler;
import com.flowpilot.messaging.config.RabbitTopology;
import com.flowpilot.messaging.outbox.MessageDeduplicator;
import com.flowpilot.messaging.protocol.MessageEnvelope;
import com.flowpilot.messaging.protocol.MessageType;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Consumes the scheduling events.
 *
 * <p>It handles the start of a workflow and the completion or failure of a node, and it drives
 * the DAG forward. It never executes a node itself.
 */
@Slf4j
@Component
public class SchedulerConsumer extends AbstractEnvelopeConsumer {

    private final WorkflowScheduler scheduler;
    private final NodeFailureHandler failureHandler;

    public SchedulerConsumer(MessageDeduplicator deduplicator, TransactionTemplate transactionTemplate,
                             WorkflowScheduler scheduler, NodeFailureHandler failureHandler) {
        super(deduplicator, transactionTemplate);
        this.scheduler = scheduler;
        this.failureHandler = failureHandler;
    }

    @Override
    protected String consumerName() {
        return "workflow-scheduler";
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_WORKFLOW_SCHEDULER, concurrency = "2-8")
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
            case WORKFLOW_STARTED -> scheduler.onWorkflowStarted(envelope.getWorkflowExecutionId());
            case NODE_COMPLETED -> {
                String nodeKey = (String) envelope.payloadAsMap().get("nodeKey");
                scheduler.onNodeCompleted(envelope.getWorkflowExecutionId(), nodeKey);
            }
            case NODE_FAILED -> {
                String nodeKey = (String) envelope.payloadAsMap().get("nodeKey");
                failureHandler.onNodeFailed(envelope.getWorkflowExecutionId(), nodeKey);
            }
            default -> log.debug("message type {} is not handled by the scheduler", type);
        }
    }
}
