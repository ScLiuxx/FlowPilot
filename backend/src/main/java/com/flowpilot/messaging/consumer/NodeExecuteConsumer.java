package com.flowpilot.messaging.consumer;

import com.flowpilot.common.util.JsonUtils;
import com.flowpilot.execution.worker.NodeWorker;
import com.flowpilot.messaging.config.RabbitTopology;
import com.flowpilot.messaging.outbox.MessageDeduplicator;
import com.flowpilot.messaging.protocol.MessageEnvelope;
import com.flowpilot.messaging.protocol.payloads.NodeExecutePayload;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Consumes the node execution commands.
 *
 * <p>The node execution reaches out to external systems, so it does not run inside a database
 * transaction. The business idempotency comes from the node lease instead: the worker checks the
 * node state and the attempt number before it starts, and a stale worker cannot commit a result.
 */
@Slf4j
@Component
public class NodeExecuteConsumer extends AbstractEnvelopeConsumer {

    private final NodeWorker worker;

    public NodeExecuteConsumer(MessageDeduplicator deduplicator, TransactionTemplate transactionTemplate,
                               NodeWorker worker) {
        super(deduplicator, transactionTemplate);
        this.worker = worker;
    }

    @Override
    protected String consumerName() {
        return "node-worker";
    }

    @Override
    protected boolean handleInTransaction() {
        return false;
    }

    @RabbitListener(queues = RabbitTopology.QUEUE_NODE_EXECUTE, concurrency = "2-8")
    public void listen(Message message, Channel channel) throws Exception {
        onMessage(message, channel);
    }

    @Override
    protected void handle(MessageEnvelope<?> envelope) {
        NodeExecutePayload payload = JsonUtils.mapper()
                .convertValue(envelope.getPayload(), NodeExecutePayload.class);
        worker.execute(payload, envelope.getWorkflowExecutionId(), envelope.getNodeExecutionId(),
                envelope.getAttemptNo());
    }
}
