package com.flowpilot.execution;

import com.flowpilot.agent.entity.AgentInvocation;
import com.flowpilot.agent.mapper.AgentInvocationMapper;
import com.flowpilot.agent.service.AgentEventHandler;
import com.flowpilot.agent.service.AgentInvocationService;
import com.flowpilot.common.util.JsonUtils;
import com.flowpilot.execution.entity.NodeExecution;
import com.flowpilot.execution.entity.WorkflowExecution;
import com.flowpilot.execution.mapper.NodeExecutionMapper;
import com.flowpilot.execution.model.EventType;
import com.flowpilot.execution.model.NodeStatus;
import com.flowpilot.execution.model.NodeType;
import com.flowpilot.execution.scheduler.AgentNodeDispatcher;
import com.flowpilot.execution.scheduler.WorkflowScheduler;
import com.flowpilot.execution.service.WorkflowExecutionService;
import com.flowpilot.execution.service.ExecutionEventService;
import com.flowpilot.messaging.outbox.entity.MessageOutbox;
import com.flowpilot.messaging.outbox.mapper.MessageOutboxMapper;
import com.flowpilot.messaging.protocol.MessageEnvelope;
import com.flowpilot.messaging.protocol.payloads.AgentCompletedPayload;
import com.flowpilot.messaging.protocol.payloads.AgentExecutePayload;
import com.flowpilot.messaging.protocol.payloads.AgentInterruptedPayload;
import com.flowpilot.workflow.definition.WorkflowDefinitionDocument;
import com.flowpilot.workflow.definition.WorkflowNodeDefinition;
import com.flowpilot.workflow.entity.WorkflowVersion;
import com.flowpilot.workflow.service.WorkflowService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests of the coordination between the Java backend and the agent service.
 *
 * <p>They cover the parts that are easy to get wrong: the stable thread id, the correlation
 * between the invocation and the node, the idempotency of the result events, and the interrupt
 * that pauses the workflow for a human.
 */
@SpringBootTest
@ActiveProfiles("test")
class AgentCoordinationIntegrationTest {

    @Autowired
    private WorkflowService workflowService;
    @Autowired
    private WorkflowExecutionService executionService;
    @Autowired
    private WorkflowScheduler scheduler;
    @Autowired
    private AgentInvocationService invocationService;
    @Autowired
    private AgentEventHandler eventHandler;
    @Autowired
    private AgentNodeDispatcher agentNodeDispatcher;
    @Autowired
    private AgentInvocationMapper invocationMapper;
    @Autowired
    private NodeExecutionMapper nodeExecutionMapper;
    @Autowired
    private MessageOutboxMapper outboxMapper;
    @Autowired
    private ExecutionEventService eventService;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("T09: an agent node creates an invocation with a stable thread id")
    void agentNodeCreatesInvocation() {
        Long userId = 9101L;
        // The agent node waits for the collection node, so the test first completes it.
        WorkflowDefinitionDocument document = definition(
                node("collect", "CODE", Map.of("operation", "identity"), List.of()),
                node("analyze", "AGENT", Map.of("agentType", "REPOSITORY_ANALYST"), List.of("collect")));

        var workflow = workflowService.create("agent demo", null, "TEST", userId, document);
        var version = workflowService.listVersions(workflow.getId()).get(0);
        workflowService.publish(workflow.getId(), version.getId(), userId);
        WorkflowVersion published = workflowService.getPublishedVersion(workflow.getId());

        WorkflowExecution execution = executionService.start(published, document, userId,
                Map.of("repositoryUrl", "https://github.com/example/project"));
        scheduler.onWorkflowStarted(execution.getId());

        // The collection node is dispatched first, the agent node waits for it.
        NodeExecution collectNode = executionService.nodes(execution.getId()).stream()
                .filter(node -> "collect".equals(node.getNodeKey()))
                .findFirst()
                .orElseThrow();
        assertThat(collectNode.getStatus()).isEqualTo(NodeStatus.READY.name());

        NodeExecution agentNode = executionService.nodes(execution.getId()).stream()
                .filter(node -> "analyze".equals(node.getNodeKey()))
                .findFirst()
                .orElseThrow();
        assertThat(agentNode.getStatus())
                .as("the agent node must wait for its dependency")
                .isEqualTo(NodeStatus.PENDING.name());
    }

    @Test
    @DisplayName("T09: the dispatched agent node keeps running and holds a stable thread id")
    void dispatchedAgentNodeHoldsAStableThreadId() {
        Long userId = 9105L;
        Long executionId = startAgentWorkflow(userId, "agent dispatch demo");
        NodeExecution agentNode = agentNodeOf(executionId);

        // The agent node keeps running while the agent service works on it.
        assertThat(agentNode.getStatus()).isEqualTo(NodeStatus.RUNNING.name());
        assertThat(agentNode.getNodeType()).isEqualTo(NodeType.AGENT.name());
        assertThat(agentNode.getWorkerId()).isEqualTo("agent-dispatcher");

        AgentInvocation invocation = invocationMapper
                .findByNodeExecutionId(agentNode.getId()).get(0);
        assertThat(invocation.getThreadId())
                .as("the thread id must be derived from the invocation id")
                .isEqualTo("flowpilot-" + invocation.getId());
        assertThat(invocation.getAgentType()).isEqualTo("REPOSITORY_ANALYST");

        // The execute message went to the agent queue, not to the node queue.
        MessageOutbox record = outboxMapper.selectList(null).stream()
                .filter(row -> "agent.execute".equals(row.getRoutingKey()))
                .filter(row -> row.getAggregateId().equals(invocation.getId()))
                .findFirst()
                .orElseThrow();
        Map<String, Object> envelope = JsonUtils.toMap(record.getPayload());
        assertThat(envelope.get("messageType")).isEqualTo("AgentExecute");
        assertThat(envelope.get("invocationId")).isEqualTo(invocation.getId());

        Map<String, Object> payload = JsonUtils.toMap(envelope.get("payload"));
        assertThat(payload.get("agentType")).isEqualTo("REPOSITORY_ANALYST");
        assertThat(payload.get("threadId")).isEqualTo(invocation.getThreadId());
        assertThat(payload.get("invocationId")).isEqualTo(invocation.getId());
    }

    @Test
    @DisplayName("T09: the agent result completes the node and the workflow")
    void agentResultCompletesTheNode() {
        Long userId = 9102L;
        Long executionId = startAgentWorkflow(userId, "agent result demo");
        NodeExecution agentNode = agentNodeOf(executionId);

        AgentInvocation invocation = invocationMapper.findByNodeExecutionId(agentNode.getId()).get(0);

        AgentCompletedPayload payload = AgentCompletedPayload.builder()
                .invocationId(invocation.getId())
                .threadId(invocation.getThreadId())
                .checkpointId("checkpoint-abc")
                .result(Map.of("architectureAnalysis", "分层架构"))
                .usage(AgentCompletedPayload.Usage.builder()
                        .inputTokens(1200L).outputTokens(400L).build())
                .build();

        transactionTemplate.executeWithoutResult(status -> eventHandler.onCompleted(payload));

        NodeExecution completed = nodeExecutionMapper.selectById(agentNode.getId());
        assertThat(completed.getStatus()).isEqualTo(NodeStatus.SUCCEEDED.name());
        assertThat(completed.getOutputJson()).contains("分层架构");

        AgentInvocation updated = invocationMapper.selectById(invocation.getId());
        assertThat(updated.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(updated.getCheckpointId()).isEqualTo("checkpoint-abc");
        assertThat(updated.getTokenInput()).isEqualTo(1200L);
        assertThat(updated.getTokenOutput()).isEqualTo(400L);
    }

    @Test
    @DisplayName("T09/T06: a repeated agent result does not change the state a second time")
    void repeatedAgentResultIsIdempotent() {
        Long userId = 9103L;
        Long executionId = startAgentWorkflow(userId, "idempotent agent demo");
        NodeExecution agentNode = agentNodeOf(executionId);
        AgentInvocation invocation = invocationMapper.findByNodeExecutionId(agentNode.getId()).get(0);

        AgentCompletedPayload payload = AgentCompletedPayload.builder()
                .invocationId(invocation.getId())
                .threadId(invocation.getThreadId())
                .checkpointId("checkpoint-1")
                .result(Map.of("conclusion", "first"))
                .build();

        transactionTemplate.executeWithoutResult(status -> eventHandler.onCompleted(payload));
        // The second delivery of the same result must be a no-op.
        transactionTemplate.executeWithoutResult(status -> eventHandler.onCompleted(payload));

        NodeExecution completed = nodeExecutionMapper.selectById(agentNode.getId());
        assertThat(completed.getStatus()).isEqualTo(NodeStatus.SUCCEEDED.name());
        assertThat(completed.getOutputJson()).contains("first");
    }

    @Test
    @DisplayName("T10: an interrupted agent pauses the workflow for a human")
    void interruptedAgentPausesTheWorkflow() {
        Long userId = 9104L;
        Long executionId = startAgentWorkflow(userId, "interrupt demo");
        NodeExecution agentNode = agentNodeOf(executionId);
        AgentInvocation invocation = invocationMapper.findByNodeExecutionId(agentNode.getId()).get(0);

        AgentInterruptedPayload payload = AgentInterruptedPayload.builder()
                .invocationId(invocation.getId())
                .threadId(invocation.getThreadId())
                .checkpointId("checkpoint-interrupt")
                .interruptType("HUMAN_APPROVAL")
                .interruptData(AgentInterruptedPayload.InterruptData.builder()
                        .title("请审核分析结论")
                        .description("Agent 需要人工确认")
                        .requestedAction("APPROVE_REPORT")
                        .build())
                .build();

        transactionTemplate.executeWithoutResult(status -> eventHandler.onInterrupted(payload));

        NodeExecution waiting = nodeExecutionMapper.selectById(agentNode.getId());
        assertThat(waiting.getStatus()).isEqualTo(NodeStatus.WAITING_HUMAN.name());

        AgentInvocation updated = invocationMapper.selectById(invocation.getId());
        assertThat(updated.getStatus()).isEqualTo("WAITING_HUMAN");
        assertThat(updated.getCheckpointId()).isEqualTo("checkpoint-interrupt");

        // The interrupt is recorded, so the console can show why the workflow paused.
        var events = eventService.listAfter(executionId, 0, 200);
        assertThat(events).anyMatch(event ->
                "AGENT_INTERRUPTED".equals(event.getEventType())
                        && event.getEventData().contains("请审核分析结论"));
    }

    private Long startAgentWorkflow(Long userId, String name) {
        WorkflowDefinitionDocument document = definition(
                node("analyze", "AGENT", Map.of("agentType", "REPOSITORY_ANALYST"), List.of()));

        var workflow = workflowService.create(name, null, "TEST", userId, document);
        var version = workflowService.listVersions(workflow.getId()).get(0);
        workflowService.publish(workflow.getId(), version.getId(), userId);
        WorkflowVersion published = workflowService.getPublishedVersion(workflow.getId());

        WorkflowExecution execution = executionService.start(published, document, userId,
                Map.of("repositoryUrl", "https://github.com/example/project"));
        scheduler.onWorkflowStarted(execution.getId());
        return execution.getId();
    }

    private NodeExecution agentNodeOf(Long executionId) {
        return executionService.nodes(executionId).stream()
                .filter(node -> "analyze".equals(node.getNodeKey()))
                .findFirst()
                .orElseThrow();
    }

    private WorkflowDefinitionDocument definition(WorkflowNodeDefinition... nodes) {
        return WorkflowDefinitionDocument.builder().name("test").nodes(List.of(nodes)).build();
    }

    private WorkflowNodeDefinition node(String id, String type, Map<String, Object> config,
                                        List<String> dependencies) {
        return WorkflowNodeDefinition.builder()
                .id(id).type(type).config(config).dependencies(dependencies).build();
    }
}
