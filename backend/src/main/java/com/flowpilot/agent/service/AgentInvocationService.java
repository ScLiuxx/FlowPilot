package com.flowpilot.agent.service;

import com.flowpilot.common.exception.BizException;
import com.flowpilot.common.id.SnowflakeIdGenerator;
import com.flowpilot.common.result.ErrorCode;
import com.flowpilot.common.trace.TraceContext;
import com.flowpilot.common.util.JsonUtils;
import com.flowpilot.agent.entity.AgentInvocation;
import com.flowpilot.agent.mapper.AgentInvocationMapper;
import com.flowpilot.execution.entity.NodeExecution;
import com.flowpilot.execution.entity.WorkflowExecution;
import com.flowpilot.execution.mapper.NodeExecutionMapper;
import com.flowpilot.execution.mapper.WorkflowExecutionMapper;
import com.flowpilot.execution.model.EventType;
import com.flowpilot.execution.service.ExecutionEventService;
import com.flowpilot.messaging.outbox.OutboxService;
import com.flowpilot.messaging.protocol.MessageEnvelope;
import com.flowpilot.messaging.protocol.MessageType;
import com.flowpilot.messaging.protocol.payloads.AgentExecutePayload;
import com.flowpilot.messaging.protocol.payloads.AgentResumePayload;
import com.flowpilot.workflow.definition.WorkflowDefinitionDocument;
import com.flowpilot.workflow.definition.WorkflowNodeDefinition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manages the correspondence between a Java node execution and a LangGraph execution.
 *
 * <p>The stable identifier of the whole chain is the invocation id. The LangGraph thread id is
 * derived from it as {@code flowpilot-{invocationId}}, which means a redelivered
 * {@code AgentExecute} message still points at the same checkpoint and the agent can continue
 * where it stopped instead of starting over.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentInvocationService {

    private static final DateTimeFormatter INVOCATION_NO_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd");

    private final AgentInvocationMapper invocationMapper;
    private final NodeExecutionMapper nodeExecutionMapper;
    private final WorkflowExecutionMapper executionMapper;
    private final ExecutionEventService eventService;
    private final OutboxService outboxService;
    private final SnowflakeIdGenerator idGenerator;

    /**
     * The longest an agent invocation may run. It is the limit of the platform, so a node cannot
     * occupy a worker longer than the platform allows.
     */
    @Value("${flowpilot.agent.max-execution-seconds:900}")
    private long maxExecutionSeconds;

    /**
     * Creates the invocation for an agent node and publishes the execute message.
     *
     * <p>The node is put into the running state here, because the agent service owns the
     * execution from this point on and the Java worker must not treat it as a normal node.
     */
    @Transactional
    public AgentInvocation dispatch(NodeExecution node, WorkflowNodeDefinition definition,
                                    Map<String, Object> input, String agentType, String traceId) {
        int attemptNo = (node.getCurrentAttempt() == null ? 0 : node.getCurrentAttempt()) + 1;

        // An existing invocation for the same attempt is reused, so a redispatch after a recovery
        // keeps the same thread and therefore the same checkpoint.
        AgentInvocation existing = invocationMapper
                .findByNodeExecutionId(node.getId()).stream()
                .filter(invocation -> invocation.getAttemptNo() == attemptNo)
                .findFirst()
                .orElse(null);

        AgentInvocation invocation = existing;
        if (invocation == null) {
            long invocationId = idGenerator.nextId();
            invocation = AgentInvocation.builder()
                    .id(invocationId)
                    .invocationNo(buildInvocationNo(invocationId))
                    .workflowExecutionId(node.getWorkflowExecutionId())
                    .nodeExecutionId(node.getId())
                    .attemptNo(attemptNo)
                    .agentType(agentType)
                    .threadId(AgentExecutePayload.threadIdFor(invocationId))
                    .status(AgentInvocation.STATUS_PENDING)
                    .tokenInput(0L)
                    .tokenOutput(0L)
                    .startedAt(LocalDateTime.now())
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            invocationMapper.insert(invocation);
        }

        // The node keeps running while the agent works. The lease is taken here, because the
        // Java worker must not treat the agent node as a normal node.
        int leased = nodeExecutionMapper.acquireLease(node.getId(), "agent-dispatcher", attemptNo,
                node.getCurrentAttempt() == null ? 0 : node.getCurrentAttempt(),
                LocalDateTime.now().plusHours(2));
        if (leased != 1) {
            log.warn("the agent node {} could not be leased, another worker owns it", node.getNodeKey());
        }

        AgentExecutePayload payload = AgentExecutePayload.builder()
                .agentType(agentType)
                .threadId(invocation.getThreadId())
                .invocationId(invocation.getId())
                .input(input == null ? Map.of() : input)
                .config(buildConfig(definition))
                .build();

        MessageEnvelope<AgentExecutePayload> envelope = MessageEnvelope.create(
                MessageType.AGENT_EXECUTE, payload);
        envelope.setWorkflowExecutionId(node.getWorkflowExecutionId());
        envelope.setNodeExecutionId(node.getId());
        envelope.setAttemptNo(attemptNo);
        envelope.setInvocationId(invocation.getId());
        // The agent service continues the trace of the execution, so the agent spans and the
        // node spans end up in the same trace.
        envelope.setTraceId(traceId == null || traceId.isBlank()
                ? TraceContext.currentTraceId() : traceId);
        outboxService.enqueue(MessageType.AGENT_EXECUTE, "AGENT", invocation.getId(), envelope);

        eventService.append(node.getWorkflowExecutionId(), node.getId(), EventType.AGENT_STARTED,
                Map.of("nodeKey", node.getNodeKey(), "agentType", agentType,
                        "invocationId", invocation.getId(), "threadId", invocation.getThreadId()), null);

        log.info("agent invocation dispatched invocationId={} threadId={} node={}",
                invocation.getId(), invocation.getThreadId(), node.getNodeKey());
        return invocation;
    }

    /**
     * Publishes the resume command for a paused agent.
     *
     * @param resumeRequestId idempotency key of the resume, a repeated request is ignored
     */
    @Transactional
    public void resume(Long invocationId, String resumeRequestId, Long approvalId,
                       Map<String, Object> resumeValue) {
        AgentInvocation invocation = requireInvocation(invocationId);
        if (AgentInvocation.STATUS_SUCCEEDED.equals(invocation.getStatus())
                || AgentInvocation.STATUS_FAILED.equals(invocation.getStatus())) {
            log.info("agent invocation {} already finished, ignoring the resume", invocationId);
            return;
        }

        AgentResumePayload payload = AgentResumePayload.builder()
                .invocationId(invocation.getId())
                .threadId(invocation.getThreadId())
                .resumeRequestId(resumeRequestId)
                .approvalId(approvalId)
                .resumeValue(resumeValue == null ? Map.of() : resumeValue)
                .build();

        MessageEnvelope<AgentResumePayload> envelope = MessageEnvelope.create(
                MessageType.AGENT_RESUME, payload);
        envelope.setWorkflowExecutionId(invocation.getWorkflowExecutionId());
        envelope.setNodeExecutionId(invocation.getNodeExecutionId());
        envelope.setAttemptNo(invocation.getAttemptNo());
        envelope.setInvocationId(invocation.getId());
        outboxService.enqueue(MessageType.AGENT_RESUME, "AGENT", invocation.getId(), envelope);

        invocation.setStatus(AgentInvocation.STATUS_RUNNING);
        invocationMapper.updateById(invocation);

        eventService.append(invocation.getWorkflowExecutionId(), invocation.getNodeExecutionId(),
                EventType.AGENT_RESUMED,
                Map.of("invocationId", invocation.getId(), "resumeRequestId", resumeRequestId), null);
        log.info("agent resume published invocationId={} resumeRequestId={}",
                invocationId, resumeRequestId);
    }

    /**
     * Publishes the cancel command for a running agent.
     */
    @Transactional
    public void cancel(Long invocationId, String reason) {
        AgentInvocation invocation = invocationMapper.selectById(invocationId);
        if (invocation == null) {
            return;
        }
        var payload = com.flowpilot.messaging.protocol.payloads.AgentCancelPayload.builder()
                .invocationId(invocation.getId())
                .threadId(invocation.getThreadId())
                .cancelRequestId(java.util.UUID.randomUUID().toString())
                .cancelReason(reason)
                .build();

        MessageEnvelope<?> envelope = MessageEnvelope.create(MessageType.AGENT_CANCEL, payload);
        envelope.setWorkflowExecutionId(invocation.getWorkflowExecutionId());
        envelope.setNodeExecutionId(invocation.getNodeExecutionId());
        envelope.setAttemptNo(invocation.getAttemptNo());
        envelope.setInvocationId(invocation.getId());
        outboxService.enqueue(MessageType.AGENT_CANCEL, "AGENT", invocation.getId(), envelope);
    }

    public AgentInvocation requireInvocation(Long invocationId) {
        AgentInvocation invocation = invocationMapper.selectById(invocationId);
        if (invocation == null) {
            throw new BizException(ErrorCode.AG_INVOCATION_NOT_FOUND);
        }
        return invocation;
    }

    public AgentInvocation findByThreadId(String threadId) {
        return invocationMapper.findByThreadId(threadId);
    }

    /**
     * Builds the configuration the agent service receives, including the execution budgets.
     */
    private Map<String, Object> buildConfig(WorkflowNodeDefinition definition) {
        Map<String, Object> config = new LinkedHashMap<>();
        if (definition == null) {
            return config;
        }
        if (definition.getConfig() != null) {
            config.putAll(definition.getConfig());
        }
        if (definition.getTimeoutPolicy() != null && definition.getTimeoutPolicy().getTimeoutSeconds() != null) {
            config.put("timeoutSeconds", definition.getTimeoutPolicy().getTimeoutSeconds());
        } else {
            // Without a timeout of the node the agent service uses its own default. The platform
            // limit is the smaller of the two, so a node cannot run longer than the platform
            // allows.
            config.put("timeoutSeconds", Math.min(maxExecutionSeconds,
                    config.getOrDefault("timeoutSeconds", maxExecutionSeconds) instanceof Number number
                            ? number.longValue() : maxExecutionSeconds));
        }
        return config;
    }

    private String buildInvocationNo(Long invocationId) {
        return "AI-" + LocalDateTime.now().format(INVOCATION_NO_FORMAT) + "-"
                + String.format("%06d", Math.abs(invocationId % 1_000_000));
    }

    /**
     * The workflow input of an execution, used as the agent input.
     */
    public Map<String, Object> workflowInput(Long workflowExecutionId) {
        WorkflowExecution execution = executionMapper.selectById(workflowExecutionId);
        if (execution == null) {
            return Map.of();
        }
        return JsonUtils.toMap(execution.getInputJson());
    }
}
