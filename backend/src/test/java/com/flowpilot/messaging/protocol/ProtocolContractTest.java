package com.flowpilot.messaging.protocol;

import com.flowpilot.common.util.JsonUtils;
import com.flowpilot.messaging.protocol.payloads.AgentCompletedPayload;
import com.flowpilot.messaging.protocol.payloads.AgentExecutePayload;
import com.flowpilot.messaging.protocol.payloads.AgentFailedPayload;
import com.flowpilot.messaging.protocol.payloads.AgentInterruptedPayload;
import com.flowpilot.messaging.protocol.payloads.AgentResumePayload;
import com.flowpilot.messaging.protocol.payloads.NodeCompletedPayload;
import com.flowpilot.messaging.protocol.payloads.NodeExecutePayload;
import com.flowpilot.messaging.protocol.payloads.NodeFailedPayload;
import com.flowpilot.messaging.protocol.payloads.WorkflowStartedPayload;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests against the shared JSON Schema files.
 *
 * <p>The Python agent service validates its messages against the very same files. These tests make
 * sure the Java side produces envelopes that the Python side will accept, so a protocol drift is
 * caught here instead of in production.
 */
class ProtocolContractTest {

    private static final Path PROTOCOL_DIR = Path.of("..", "flowpilot-protocol");

    private static JsonSchemaFactory factory;

    @BeforeAll
    static void loadSchemas() {
        factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
    }

    @Test
    @DisplayName("the shared protocol files are present")
    void protocolFilesExist() {
        assertThat(PROTOCOL_DIR.resolve("common/envelope.schema.json")).exists();
        assertThat(PROTOCOL_DIR.resolve("agent/agent-execute.schema.json")).exists();
        assertThat(PROTOCOL_DIR.resolve("node/node-execute.schema.json")).exists();
    }

    @Test
    @DisplayName("the envelope of every message type conforms to the shared schema")
    void envelopeConformsToSchema() {
        JsonSchema envelopeSchema = schema("common/envelope.schema.json");

        List<MessageEnvelope<?>> envelopes = List.of(
                envelope(MessageType.WORKFLOW_STARTED, WorkflowStartedPayload.builder()
                        .workflowId(100L).workflowVersionId(101L).executionId(10001L)
                        .input(Map.of("repositoryUrl", "https://github.com/example/project"))
                        .build()),
                envelope(MessageType.NODE_EXECUTE, NodeExecutePayload.builder()
                        .nodeKey("collect_repository").nodeType("HTTP")
                        .config(Map.of("operation", "get_repository"))
                        .input(Map.of("owner", "example")).timeoutSeconds(30)
                        .build()),
                envelope(MessageType.NODE_COMPLETED, NodeCompletedPayload.builder()
                        .nodeKey("collect_repository").result(Map.of("stars", 12000)).build()),
                envelope(MessageType.NODE_FAILED, NodeFailedPayload.builder()
                        .nodeKey("collect_repository").errorCode("GITHUB_API_TIMEOUT")
                        .errorCategory("TRANSIENT").errorMessage("timed out").retryable(true)
                        .build()),
                envelope(MessageType.AGENT_EXECUTE, AgentExecutePayload.builder()
                        .agentType("REPOSITORY_ANALYST").threadId("flowpilot-30001")
                        .invocationId(30001L).input(Map.of("owner", "example"))
                        .config(Map.of("maxToolCalls", 20, "timeoutSeconds", 300))
                        .build()),
                envelope(MessageType.AGENT_COMPLETED, AgentCompletedPayload.builder()
                        .invocationId(30001L).threadId("flowpilot-30001")
                        .checkpointId("checkpoint-001")
                        .result(Map.of("architectureAnalysis", "分层架构"))
                        .usage(AgentCompletedPayload.Usage.builder()
                                .inputTokens(5000L).outputTokens(2000L).build())
                        .build()),
                envelope(MessageType.AGENT_FAILED, AgentFailedPayload.builder()
                        .invocationId(30001L).threadId("flowpilot-30001")
                        .errorCode("LLM_REQUEST_TIMEOUT").errorCategory("TRANSIENT")
                        .errorMessage("model request timed out").retryable(true)
                        .build()),
                envelope(MessageType.AGENT_INTERRUPTED, AgentInterruptedPayload.builder()
                        .invocationId(30001L).threadId("flowpilot-30001")
                        .checkpointId("checkpoint-002").interruptType("HUMAN_APPROVAL")
                        .interruptData(AgentInterruptedPayload.InterruptData.builder()
                                .title("请审核技术尽调报告").description("需要人工确认")
                                .requestedAction("APPROVE_REPORT").build())
                        .build()),
                envelope(MessageType.AGENT_RESUME, AgentResumePayload.builder()
                        .invocationId(30001L).threadId("flowpilot-30001")
                        .resumeRequestId("resume-001").approvalId(40001L)
                        .resumeValue(Map.of("approved", true)).build()));

        for (MessageEnvelope<?> envelope : envelopes) {
            String json = JsonUtils.toJson(envelope);
            Set<ValidationMessage> violations = envelopeSchema.validate(readTree(json));
            assertThat(violations)
                    .as("violations for %s: %s", envelope.getMessageType(), json)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("the payload of every agent message conforms to the shared schema")
    void agentPayloadsConformToSchema() {
        assertPayload("agent/agent-execute.schema.json", AgentExecutePayload.builder()
                .agentType("REPOSITORY_ANALYST").threadId("flowpilot-30001")
                .invocationId(30001L).input(Map.of("owner", "example"))
                .config(Map.of("model", "configured-model", "maxIterations", 10,
                        "maxToolCalls", 20, "timeoutSeconds", 300))
                .build());

        assertPayload("agent/agent-resume.schema.json", AgentResumePayload.builder()
                .invocationId(30001L).threadId("flowpilot-30001")
                .resumeRequestId("resume-001").approvalId(40001L)
                .resumeValue(Map.of("approved", true, "comment", "符合要求"))
                .build());

        assertPayload("agent/agent-completed.schema.json", AgentCompletedPayload.builder()
                .invocationId(30001L).threadId("flowpilot-30001")
                .checkpointId("checkpoint-001")
                .result(Map.of("architectureAnalysis", "分层架构"))
                .usage(AgentCompletedPayload.Usage.builder()
                        .inputTokens(5000L).outputTokens(2000L)
                        .llmCalls(3L).toolCalls(7L).toolFailures(1L).build())
                .build());

        assertPayload("agent/agent-failed.schema.json", AgentFailedPayload.builder()
                .invocationId(30001L).threadId("flowpilot-30001")
                .errorCode("LLM_REQUEST_TIMEOUT").errorCategory("TRANSIENT")
                .errorMessage("model request timed out").retryable(true)
                .build());

        assertPayload("agent/agent-interrupted.schema.json", AgentInterruptedPayload.builder()
                .invocationId(30001L).threadId("flowpilot-30001")
                .checkpointId("checkpoint-002").interruptType("HUMAN_APPROVAL")
                .interruptData(AgentInterruptedPayload.InterruptData.builder()
                        .title("请审核").description("需要人工确认")
                        .requestedAction("APPROVE_REPORT")
                        .payload(Map.of("repository", "example/project")).build())
                .build());
    }

    @Test
    @DisplayName("the payload of every node message conforms to the shared schema")
    void nodePayloadsConformToSchema() {
        assertPayload("workflow/workflow-started.schema.json", WorkflowStartedPayload.builder()
                .workflowId(100L).workflowVersionId(101L).executionId(10001L)
                .input(Map.of("repositoryUrl", "https://github.com/example/project"))
                .build());

        assertPayload("node/node-execute.schema.json", NodeExecutePayload.builder()
                .nodeKey("collect_repository").nodeType("HTTP")
                .config(Map.of("operation", "get_repository"))
                .input(Map.of("owner", "example")).timeoutSeconds(30)
                .build());

        assertPayload("node/node-completed.schema.json", NodeCompletedPayload.builder()
                .nodeKey("collect_repository")
                .result(Map.of("repositoryId", 123456, "fullName", "example/project")).build());

        assertPayload("node/node-failed.schema.json", NodeFailedPayload.builder()
                .nodeKey("collect_repository").errorCode("GITHUB_API_TIMEOUT")
                .errorCategory("TRANSIENT").errorMessage("GitHub API request timeout")
                .retryable(true).build());
    }

    @Test
    @DisplayName("the thread id is derived from the invocation id, so a resume keeps the thread")
    void threadIdIsDerivedFromInvocation() {
        String threadId = AgentExecutePayload.threadIdFor(30001L);

        assertThat(threadId).isEqualTo("flowpilot-30001");
        // The agent service rejects a thread id that does not match the invocation id.
        assertThat(threadId).isEqualTo("flowpilot-" + 30001L);
    }

    private void assertPayload(String schemaPath, Object payload) {
        JsonSchema payloadSchema = schema(schemaPath);
        String json = JsonUtils.toJson(payload);
        List<String> violations = new ArrayList<>();
        for (ValidationMessage violation : payloadSchema.validate(readTree(json))) {
            violations.add(violation.getMessage());
        }
        assertThat(violations).as("violations for %s: %s", schemaPath, json).isEmpty();
    }

    private com.fasterxml.jackson.databind.JsonNode readTree(String json) {
        try {
            return JsonUtils.mapper().readTree(json);
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot parse the JSON " + json, ex);
        }
    }

    private MessageEnvelope<?> envelope(MessageType type, Object payload) {
        MessageEnvelope<?> envelope = MessageEnvelope.create(type, payload);
        envelope.setWorkflowExecutionId(10001L);
        envelope.setNodeExecutionId(20001L);
        envelope.setAttemptNo(1);
        envelope.setInvocationId(30001L);
        // The span of the producer, so the consumer can continue the trace.
        envelope.setSpanId("a1b2c3d4e5f60718");
        return envelope;
    }

    private JsonSchema schema(String relativePath) {
        Path path = PROTOCOL_DIR.resolve(relativePath);
        assertThat(path).as("protocol schema %s", relativePath).exists();
        try (InputStream in = Files.newInputStream(path)) {
            return factory.getSchema(in);
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot load the protocol schema " + path, ex);
        }
    }
}
