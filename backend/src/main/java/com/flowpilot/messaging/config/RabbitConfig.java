package com.flowpilot.messaging.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the broker topology and configures the template used to publish messages.
 *
 * <p>Publisher confirms are enabled so the outbox publisher only marks a row as published after
 * the broker has actually taken responsibility for the message.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class RabbitConfig {

    @Bean
    public TopicExchange flowpilotExchange() {
        return new TopicExchange(RabbitTopology.EXCHANGE, true, false);
    }

    @Bean
    public TopicExchange deadLetterExchange() {
        return new TopicExchange(RabbitTopology.DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    public Queue workflowSchedulerQueue() {
        return durableWithDeadLetter(RabbitTopology.QUEUE_WORKFLOW_SCHEDULER);
    }

    @Bean
    public Queue nodeExecuteQueue() {
        return durableWithDeadLetter(RabbitTopology.QUEUE_NODE_EXECUTE);
    }

    @Bean
    public Queue agentExecuteQueue() {
        return durableWithDeadLetter(RabbitTopology.QUEUE_AGENT_EXECUTE);
    }

    @Bean
    public Queue agentControlQueue() {
        return durableWithDeadLetter(RabbitTopology.QUEUE_AGENT_CONTROL);
    }

    @Bean
    public Queue backendAgentEventsQueue() {
        return durableWithDeadLetter(RabbitTopology.QUEUE_BACKEND_AGENT_EVENTS);
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(RabbitTopology.QUEUE_DEAD_LETTER).build();
    }

    @Bean
    public Binding workflowSchedulerBinding(Queue workflowSchedulerQueue, TopicExchange flowpilotExchange) {
        return BindingBuilder.bind(workflowSchedulerQueue)
                .to(flowpilotExchange)
                .with("workflow.*");
    }

    @Bean
    public Binding nodeExecuteBinding(Queue nodeExecuteQueue, TopicExchange flowpilotExchange) {
        return BindingBuilder.bind(nodeExecuteQueue).to(flowpilotExchange).with("node.execute");
    }

    @Bean
    public Binding nodeCompletedBinding(Queue workflowSchedulerQueue, TopicExchange flowpilotExchange) {
        return BindingBuilder.bind(workflowSchedulerQueue).to(flowpilotExchange).with("node.completed");
    }

    @Bean
    public Binding nodeFailedBinding(Queue workflowSchedulerQueue, TopicExchange flowpilotExchange) {
        return BindingBuilder.bind(workflowSchedulerQueue).to(flowpilotExchange).with("node.failed");
    }

    @Bean
    public Binding agentExecuteBinding(Queue agentExecuteQueue, TopicExchange flowpilotExchange) {
        return BindingBuilder.bind(agentExecuteQueue).to(flowpilotExchange).with("agent.execute");
    }

    @Bean
    public Binding agentResumeBinding(Queue agentControlQueue, TopicExchange flowpilotExchange) {
        return BindingBuilder.bind(agentControlQueue).to(flowpilotExchange).with("agent.resume");
    }

    @Bean
    public Binding agentCancelBinding(Queue agentControlQueue, TopicExchange flowpilotExchange) {
        return BindingBuilder.bind(agentControlQueue).to(flowpilotExchange).with("agent.cancel");
    }

    @Bean
    public Binding backendAgentEventsBinding(Queue backendAgentEventsQueue, TopicExchange flowpilotExchange) {
        return BindingBuilder.bind(backendAgentEventsQueue).to(flowpilotExchange).with("agent.*");
    }

    @Bean
    public Binding deadLetterBinding(Queue deadLetterQueue, TopicExchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with("#");
    }

    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                        MessageConverter jsonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter);
        template.setExchange(RabbitTopology.EXCHANGE);
        template.setMandatory(true);
        template.setReturnsCallback(returned -> log.warn("message returned unroutable: {}",
                returned.getMessage().getMessageProperties().getReceivedRoutingKey()));
        template.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                log.error("publisher confirm negative ack: correlationData={} cause={}",
                        correlationData, cause);
            }
        });
        return template;
    }

    private Queue durableWithDeadLetter(String name) {
        return QueueBuilder.durable(name)
                .deadLetterExchange(RabbitTopology.DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(RabbitTopology.DEAD_LETTER_ROUTING_KEY)
                .build();
    }
}
