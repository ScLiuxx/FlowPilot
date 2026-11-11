package com.flowpilot.agent.service;

import com.flowpilot.agent.entity.AgentInvocation;
import com.flowpilot.agent.mapper.AgentInvocationMapper;
import com.flowpilot.approval.service.ApprovalService;
import com.flowpilot.common.metrics.FlowPilotMetrics;
import com.flowpilot.execution.entity.NodeExecution;
import com.flowpilot.execution.mapper.NodeExecutionMapper;
import com.flowpilot.execution.model.EventType;
import com.flowpilot.execution.service.ExecutionEventService;
import com.flowpilot.execution.service.NodeResultService;
import com.flowpilot.messaging.protocol.payloads.AgentCompletedPayload;
import com.flowpilot.messaging.protocol.payloads.AgentFailedPayload;
import com.flowpilot.messaging.protocol.payloads.AgentInterruptedPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Applies the result events the agent service sends back.
 *
 * <p>Every handler is idempotent. A repeated {@code AgentCompleted} does not change the state
 * again, because the invocation already reached a terminal state.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentEventHandler {

    private final AgentInvocationMapper invocationMapper;
    private final NodeExecutionMapper nodeExecutionMapper;
    private final NodeResultService nodeResultService;
    private final ApprovalService approvalService;
    private final ExecutionEventService eventService;
    private final FlowPilotMetrics metrics;

    /**
     * The agent finished successfully.
     */
    @Transactional
    public void onCompleted(AgentCompletedPayload payload) {
        AgentInvocation invocation = invocationMapper.selectById(payload.getInvocationId());
        if (invocation == null) {
            log.warn("agent completed for an unknown invocation {}", payload.getInvocationId());
            return;
        }
        if (AgentInvocation.STATUS_SUCCEEDED.equals(invocation.getStatus())) {
            log.info("agent invocation {} already succeeded, ignoring the duplicate event",
                    invocation.getId());
            return;
        }

        NodeExecution node = nodeExecutionMapper.selectById(invocation.getNodeExecutionId());
        if (node == null) {
            return;
        }

        invocation.setStatus(AgentInvocation.STATUS_SUCCEEDED);
        invocation.setCheckpointId(payload.getCheckpointId());
        invocation.setFinishedAt(LocalDateTime.now());
        if (payload.getUsage() != null) {
            invocation.setTokenInput(payload.getUsage().getInputTokens() == null
                    ? 0L : payload.getUsage().getInputTokens());
            invocation.setTokenOutput(payload.getUsage().getOutputTokens() == null
                    ? 0L : payload.getUsage().getOutputTokens());
            // The number of model and tool calls is reported by the agent service. The
            // architecture design asks for it, because only the caller knows how many calls
            // an invocation really needed.
            invocation.setLlmCallCount(payload.getUsage().getLlmCalls() == null
                    ? 0 : payload.getUsage().getLlmCalls().intValue());
            invocation.setToolCallCount(payload.getUsage().getToolCalls() == null
                    ? 0 : payload.getUsage().getToolCalls().intValue());
            invocation.setToolFailureCount(payload.getUsage().getToolFailures() == null
                    ? 0 : payload.getUsage().getToolFailures().intValue());
        }
        invocationMapper.updateById(invocation);

        recordMetrics(invocation, payload.getUsage());

        Map<String, Object> output = payload.getResult() == null ? Map.of() : payload.getResult();
        boolean accepted = nodeResultService.complete(node.getId(), invocation.getAttemptNo(),
                node.getLeaseToken(), output);
        if (!accepted) {
            log.warn("agent result for invocation {} was not accepted by node {}",
                    invocation.getId(), node.getId());
            return;
        }
        eventService.append(invocation.getWorkflowExecutionId(), node.getId(),
                EventType.AGENT_COMPLETED,
                Map.of("invocationId", invocation.getId(),
                        "threadId", invocation.getThreadId(),
                        "tokenInput", invocation.getTokenInput(),
                        "tokenOutput", invocation.getTokenOutput()), null);
    }

    /**
     * The agent failed. The business retry policy decides whether the node is retried.
     */
    @Transactional
    public void onFailed(AgentFailedPayload payload) {
        AgentInvocation invocation = invocationMapper.selectById(payload.getInvocationId());
        if (invocation == null) {
            log.warn("agent failed for an unknown invocation {}", payload.getInvocationId());
            return;
        }
        if (AgentInvocation.STATUS_SUCCEEDED.equals(invocation.getStatus())
                || AgentInvocation.STATUS_FAILED.equals(invocation.getStatus())) {
            log.info("agent invocation {} already reached a terminal state, ignoring the duplicate event",
                    invocation.getId());
            return;
        }

        NodeExecution node = nodeExecutionMapper.selectById(invocation.getNodeExecutionId());
        if (node == null) {
            return;
        }

        invocation.setStatus(AgentInvocation.STATUS_FAILED);
        invocation.setCheckpointId(payload.getCheckpointId());
        invocation.setErrorCode(payload.getErrorCode());
        invocation.setErrorMessage(payload.getErrorMessage());
        invocation.setFinishedAt(LocalDateTime.now());
        invocationMapper.updateById(invocation);

        boolean retryable = payload.getRetryable() == null || payload.getRetryable();
        nodeResultService.fail(node.getId(), invocation.getAttemptNo(), node.getLeaseToken(),
                payload.getErrorCode(), payload.getErrorMessage(),
                payload.getErrorCategory(), retryable, null);

        eventService.append(invocation.getWorkflowExecutionId(), node.getId(), EventType.AGENT_FAILED,
                Map.of("invocationId", invocation.getId(),
                        "errorCode", payload.getErrorCode(),
                        "errorCategory", payload.getErrorCategory()), null);
    }

    /**
     * The agent paused and waits for a human decision.
     *
     * <p>The approval request is created here, because the reviewer has to see the interruption in
     * the console and decide on it. Without the request the workflow would wait forever.
     */
    @Transactional
    public void onInterrupted(AgentInterruptedPayload payload) {
        AgentInvocation invocation = invocationMapper.selectById(payload.getInvocationId());
        if (invocation == null) {
            log.warn("agent interrupted for an unknown invocation {}", payload.getInvocationId());
            return;
        }
        if (AgentInvocation.STATUS_WAITING_HUMAN.equals(invocation.getStatus())) {
            log.info("agent invocation {} already waits for a human, ignoring the duplicate event",
                    invocation.getId());
            return;
        }

        NodeExecution node = nodeExecutionMapper.selectById(invocation.getNodeExecutionId());
        if (node == null) {
            return;
        }

        invocation.setStatus(AgentInvocation.STATUS_WAITING_HUMAN);
        invocation.setCheckpointId(payload.getCheckpointId());
        invocationMapper.updateById(invocation);

        int attemptNo = invocation.getAttemptNo() == null ? 1 : invocation.getAttemptNo();
        boolean paused = nodeResultService.markWaitingHuman(node.getId(), attemptNo);
        if (!paused) {
            log.warn("the agent node {} could not be put into the waiting state", node.getId());
            return;
        }

        Map<String, Object> interruptData = new java.util.LinkedHashMap<>();
        if (payload.getInterruptData() != null) {
            interruptData.put("title", payload.getInterruptData().getTitle());
            interruptData.put("description", payload.getInterruptData().getDescription());
            interruptData.put("requestedAction", payload.getInterruptData().getRequestedAction());
            if (payload.getInterruptData().getPayload() != null) {
                interruptData.put("payload", payload.getInterruptData().getPayload());
            }
        }
        interruptData.put("invocationId", invocation.getId());
        interruptData.put("threadId", invocation.getThreadId());
        interruptData.put("checkpointId", payload.getCheckpointId());
        interruptData.put("nodeKey", node.getNodeKey());

        eventService.append(invocation.getWorkflowExecutionId(), node.getId(),
                EventType.AGENT_INTERRUPTED, interruptData, null);

        // The reviewer decides, so the workflow waits for a human from now on.
        approvalService.request(invocation.getWorkflowExecutionId(), node.getId(),
                "AGENT_INTERRUPT",
                String.valueOf(interruptData.getOrDefault("title", "请确认 Agent 的分析结论")),
                String.valueOf(interruptData.getOrDefault("description", "Agent 已暂停，等待人工确认。")),
                interruptData);

        log.info("agent invocation {} interrupted, waiting for a human decision", invocation.getId());
    }

    /**
     * Stores the latest known checkpoint id of an invocation.
     */
    @Transactional
    public void updateCheckpoint(Long invocationId, String checkpointId) {
        AgentInvocation invocation = invocationMapper.selectById(invocationId);
        if (invocation == null) {
            return;
        }
        invocation.setCheckpointId(checkpointId);
        invocationMapper.updateById(invocation);
    }

    /**
     * Reports the numbers of the invocation to the metrics.
     *
     * <p>The architecture design asks for the duration of an agent, the number of model calls,
     * the token consumption and the success rate of the tool calls.
     */
    private void recordMetrics(AgentInvocation invocation, AgentCompletedPayload.Usage usage) {
        if (invocation.getStartedAt() != null && invocation.getFinishedAt() != null) {
            long millis = Duration.between(invocation.getStartedAt(), invocation.getFinishedAt()).toMillis();
            metrics.recordAgentDuration(invocation.getAgentType(), invocation.getStatus(), millis);
        }
        if (usage == null) {
            return;
        }
        metrics.countLlmCalls(invocation.getLlmCallCount() == null ? 0 : invocation.getLlmCallCount());
        metrics.countToolCalls(invocation.getToolCallCount() == null ? 0 : invocation.getToolCallCount(),
                invocation.getToolFailureCount() == null ? 0 : invocation.getToolFailureCount());
        metrics.countTokens(invocation.getTokenInput() == null ? 0 : invocation.getTokenInput(),
                invocation.getTokenOutput() == null ? 0 : invocation.getTokenOutput());
    }
}
