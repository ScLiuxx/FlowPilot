package com.flowpilot.messaging.protocol;

import com.flowpilot.messaging.protocol.payloads.AgentExecutePayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests of the message envelope, which is the contract between the two services.
 */
class MessageEnvelopeTest {

    @Test
    @DisplayName("creates a valid envelope")
    void createsValidEnvelope() {
        MessageEnvelope<AgentExecutePayload> envelope = MessageEnvelope.create(
                MessageType.AGENT_EXECUTE,
                AgentExecutePayload.builder()
                        .agentType("REPOSITORY_ANALYST")
                        .threadId("flowpilot-1")
                        .invocationId(1L)
                        .build());

        envelope.setWorkflowExecutionId(100L);

        envelope.validate();
        assertThat(envelope.getSchemaVersion()).isEqualTo("1.0");
        // The wire format uses the camel case name of the protocol schema.
        assertThat(envelope.getMessageType()).isEqualTo("AgentExecute");
        assertThat(envelope.getMessageId()).isNotBlank();
        assertThat(envelope.getCorrelationId()).isNotBlank();
        assertThat(envelope.getTraceId()).matches("^[0-9a-f]{32}$");
    }

    @Test
    @DisplayName("maps the message types onto the names used on the wire")
    void mapsWireNames() {
        assertThat(MessageType.WORKFLOW_STARTED.wireName()).isEqualTo("WorkflowStarted");
        assertThat(MessageType.NODE_EXECUTE.wireName()).isEqualTo("NodeExecute");
        assertThat(MessageType.AGENT_INTERRUPTED.wireName()).isEqualTo("AgentInterrupted");
        assertThat(MessageType.fromWireName("AgentCompleted")).isEqualTo(MessageType.AGENT_COMPLETED);
    }

    @Test
    @DisplayName("rejects a message with an unsupported major version")
    void rejectsUnsupportedVersion() {
        MessageEnvelope<AgentExecutePayload> envelope = MessageEnvelope.create(
                MessageType.AGENT_EXECUTE, AgentExecutePayload.builder().build());
        envelope.setSchemaVersion("2.0");

        assertThatThrownBy(envelope::validate)
                .isInstanceOf(UnsupportedProtocolVersionException.class);
    }

    @Test
    @DisplayName("requires the mandatory fields")
    void requiresMandatoryFields() {
        MessageEnvelope<AgentExecutePayload> envelope = MessageEnvelope.create(
                MessageType.AGENT_EXECUTE, AgentExecutePayload.builder().build());

        envelope.setWorkflowExecutionId(null);
        assertThatThrownBy(envelope::validate).isInstanceOf(IllegalArgumentException.class);

        envelope.setWorkflowExecutionId(1L);
        envelope.setTraceId("not-a-trace-id");
        assertThatThrownBy(envelope::validate).isInstanceOf(IllegalArgumentException.class);

        envelope.setTraceId("0123456789abcdef0123456789abcdef");
        envelope.setMessageId(null);
        assertThatThrownBy(envelope::validate).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("derives the thread id from the invocation id so a resume keeps the same thread")
    void derivesStableThreadId() {
        assertThat(AgentExecutePayload.threadIdFor(30001L)).isEqualTo("flowpilot-30001");
    }

    @Test
    @DisplayName("maps the message types onto the routing keys of the protocol")
    void mapsRoutingKeys() {
        assertThat(MessageType.WORKFLOW_STARTED.routingKey()).isEqualTo("workflow.started");
        assertThat(MessageType.NODE_EXECUTE.routingKey()).isEqualTo("node.execute");
        assertThat(MessageType.AGENT_RESUME.routingKey()).isEqualTo("agent.resume");
        assertThat(MessageType.AGENT_INTERRUPTED.routingKey()).isEqualTo("agent.interrupted");
        assertThat(MessageType.fromRoutingKey("agent.completed")).isEqualTo(MessageType.AGENT_COMPLETED);
    }

    @Test
    @DisplayName("classifies the errors by their retry behaviour")
    void classifiesErrors() {
        assertThat(ErrorCategory.TRANSIENT.isRetryableByDefault()).isTrue();
        assertThat(ErrorCategory.RATE_LIMIT.isRetryableByDefault()).isTrue();
        assertThat(ErrorCategory.INTERNAL.isRetryableByDefault()).isTrue();
        assertThat(ErrorCategory.VALIDATION.isRetryableByDefault()).isFalse();
        assertThat(ErrorCategory.PERMISSION.isRetryableByDefault()).isFalse();
        assertThat(ErrorCategory.BUSINESS.isRetryableByDefault()).isFalse();
    }

    @Test
    @DisplayName("reads the payload as a map")
    void readsPayloadAsMap() {
        MessageEnvelope<AgentExecutePayload> envelope = MessageEnvelope.create(
                MessageType.AGENT_EXECUTE,
                AgentExecutePayload.builder().agentType("REPOSITORY_ANALYST").build());
        envelope.setWorkflowExecutionId(1L);

        Map<String, Object> payload = envelope.payloadAsMap();

        assertThat(payload).containsEntry("agentType", "REPOSITORY_ANALYST");
    }
}
