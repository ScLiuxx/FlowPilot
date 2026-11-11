package com.flowpilot.execution.service;

import com.flowpilot.common.id.SnowflakeIdGenerator;
import com.flowpilot.common.util.JsonUtils;
import com.flowpilot.execution.entity.NodeExecution;
import com.flowpilot.execution.entity.NodeExecutionAttempt;
import com.flowpilot.execution.mapper.NodeExecutionAttemptMapper;
import com.flowpilot.execution.mapper.NodeExecutionMapper;
import com.flowpilot.execution.model.EventType;
import com.flowpilot.execution.model.NodeStatus;
import com.flowpilot.execution.model.RetryPolicy;
import com.flowpilot.messaging.outbox.OutboxService;
import com.flowpilot.messaging.protocol.MessageEnvelope;
import com.flowpilot.messaging.protocol.MessageType;
import com.flowpilot.messaging.protocol.payloads.NodeCompletedPayload;
import com.flowpilot.messaging.protocol.payloads.NodeFailedPayload;
import com.flowpilot.workflow.definition.RetryPolicyDefinition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Commits the outcome of a node execution.
 *
 * <p>All state changes happen in one transaction that also writes the attempt record, the
 * execution event and the outbox message. The conditional updates include the attempt number and
 * the fencing token, so a worker that lost its lease cannot overwrite the result of the worker
 * that took over.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NodeResultService {

    private final NodeExecutionMapper nodeExecutionMapper;
    private final NodeExecutionAttemptMapper attemptMapper;
    private final ExecutionEventService eventService;
    private final OutboxService outboxService;
    private final SnowflakeIdGenerator idGenerator;

    /**
     * Records the start of a node execution.
     *
     * <p>The attempt row is opened here, so the retry history is complete even if the worker
     * dies during the execution.
     */
    @Transactional
    public void startAttempt(NodeExecution node, int attemptNo, Long leaseToken, String workerId) {
        NodeExecutionAttempt attempt = attemptMapper.findByNodeAndAttempt(node.getId(), attemptNo);
        if (attempt == null) {
            attempt = NodeExecutionAttempt.builder()
                    .id(idGenerator.nextId())
                    .nodeExecutionId(node.getId())
                    .attemptNo(attemptNo)
                    .status(NodeStatus.RUNNING.name())
                    .workerId(workerId)
                    .leaseToken(leaseToken == null ? 0L : leaseToken)
                    .startedAt(LocalDateTime.now())
                    .createdAt(LocalDateTime.now())
                    .build();
            attemptMapper.insert(attempt);
        } else {
            attempt.setStatus(NodeStatus.RUNNING.name());
            attempt.setWorkerId(workerId);
            attempt.setLeaseToken(leaseToken == null ? 0L : leaseToken);
            attempt.setStartedAt(LocalDateTime.now());
            attemptMapper.updateById(attempt);
        }

        eventService.append(node.getWorkflowExecutionId(), node.getId(), EventType.NODE_STARTED,
                Map.of("nodeKey", node.getNodeKey(), "nodeType", node.getNodeType(),
                        "attempt", attemptNo), null);
    }

    /**
     * Records a successful node execution and schedules the successors.
     */
    @Transactional
    public boolean complete(Long nodeExecutionId, Integer attemptNo, Long leaseToken,
                            Map<String, Object> output) {
        NodeExecution node = nodeExecutionMapper.selectById(nodeExecutionId);
        if (node == null) {
            return false;
        }
        String outputJson = JsonUtils.toJson(output == null ? Map.of() : output);
        int updated = nodeExecutionMapper.completeSuccessfully(nodeExecutionId, attemptNo,
                leaseToken, outputJson);
        if (updated != 1) {
            log.warn("node {} result rejected, the lease or attempt no longer matches", nodeExecutionId);
            return false;
        }
        closeAttempt(node, attemptNo, NodeStatus.SUCCEEDED, outputJson, null, null);
        eventService.append(node.getWorkflowExecutionId(), nodeExecutionId, EventType.NODE_SUCCEEDED,
                Map.of("nodeKey", node.getNodeKey(), "attempt", attemptNo), null);

        MessageEnvelope<NodeCompletedPayload> envelope = MessageEnvelope.create(
                MessageType.NODE_COMPLETED,
                NodeCompletedPayload.builder()
                        .nodeKey(node.getNodeKey())
                        .result(output == null ? Map.of() : output)
                        .build());
        envelope.setWorkflowExecutionId(node.getWorkflowExecutionId());
        envelope.setNodeExecutionId(nodeExecutionId);
        envelope.setAttemptNo(attemptNo);
        outboxService.enqueue(MessageType.NODE_COMPLETED, "NODE", nodeExecutionId, envelope);
        return true;
    }

    /**
     * Records a failed attempt and decides whether the node is retried.
     */
    @Transactional
    public void fail(Long nodeExecutionId, Integer attemptNo, Long leaseToken,
                     String errorCode, String errorMessage, String errorCategory,
                     boolean retryable, RetryPolicyDefinition retryPolicyDefinition) {
        NodeExecution node = nodeExecutionMapper.selectById(nodeExecutionId);
        if (node == null) {
            return;
        }
        RetryPolicy retryPolicy = toPolicy(retryPolicyDefinition);
        int maxAttempts = Math.max(node.getMaxAttempts() == null ? 3 : node.getMaxAttempts(),
                retryPolicy.resolvedMaxAttempts());

        boolean canRetry = retryable && attemptNo < maxAttempts;
        if (canRetry) {
            Duration delay = retryPolicy.delayFor(attemptNo);
            // The moment the retry is due is persisted, so the node is only released when the
            // delay of the policy really elapsed.
            LocalDateTime nextRetryAt = LocalDateTime.now().plus(delay);
            int updated = nodeExecutionMapper.markRetryWait(nodeExecutionId, attemptNo, leaseToken,
                    errorCode, errorMessage, nextRetryAt);
            if (updated != 1) {
                log.warn("node {} failure rejected, the lease or attempt no longer matches", nodeExecutionId);
                return;
            }
            closeAttempt(node, attemptNo, NodeStatus.FAILED, null, errorCode, errorMessage);
            eventService.append(node.getWorkflowExecutionId(), nodeExecutionId, EventType.NODE_FAILED,
                    Map.of("nodeKey", node.getNodeKey(), "attempt", attemptNo,
                            "errorCode", errorCode, "willRetry", true,
                            "nextRetryAt", nextRetryAt.toString()), null);

            MessageEnvelope<NodeFailedPayload> envelope = MessageEnvelope.create(
                    MessageType.NODE_FAILED,
                    NodeFailedPayload.builder()
                            .nodeKey(node.getNodeKey())
                            .errorCode(errorCode)
                            .errorCategory(errorCategory)
                            .errorMessage(errorMessage)
                            .retryable(true)
                            .build());
            envelope.setWorkflowExecutionId(node.getWorkflowExecutionId());
            envelope.setNodeExecutionId(nodeExecutionId);
            envelope.setAttemptNo(attemptNo);
            outboxService.enqueue(MessageType.NODE_FAILED, "NODE", nodeExecutionId, envelope);
            log.info("node {} attempt {} failed, retry scheduled in {} ms: {}",
                    node.getNodeKey(), attemptNo, delay.toMillis(), errorCode);
            return;
        }

        int updated = nodeExecutionMapper.markFailed(nodeExecutionId, attemptNo, leaseToken,
                errorCode, errorMessage);
        if (updated != 1) {
            log.warn("node {} terminal failure rejected, the lease or attempt no longer matches",
                    nodeExecutionId);
            return;
        }
        closeAttempt(node, attemptNo, NodeStatus.FAILED, null, errorCode, errorMessage);
        eventService.append(node.getWorkflowExecutionId(), nodeExecutionId, EventType.NODE_FAILED,
                Map.of("nodeKey", node.getNodeKey(), "attempt", attemptNo,
                        "errorCode", errorCode, "willRetry", false), null);

        MessageEnvelope<NodeFailedPayload> envelope = MessageEnvelope.create(
                MessageType.NODE_FAILED,
                NodeFailedPayload.builder()
                        .nodeKey(node.getNodeKey())
                        .errorCode(errorCode)
                        .errorCategory(errorCategory)
                        .errorMessage(errorMessage)
                        .retryable(false)
                        .build());
        envelope.setWorkflowExecutionId(node.getWorkflowExecutionId());
        envelope.setNodeExecutionId(nodeExecutionId);
        envelope.setAttemptNo(attemptNo);
        outboxService.enqueue(MessageType.NODE_FAILED, "NODE", nodeExecutionId, envelope);
        log.info("node {} failed terminally after {} attempt(s): {}", node.getNodeKey(), attemptNo, errorCode);
    }

    /**
     * Moves the node into the human waiting state.
     *
     * <p>The attempt is closed, because the worker is done: it waits for a decision now. When the
     * reviewer approves, the same attempt continues and ends as successful.
     */
    @Transactional
    public boolean markWaitingHuman(Long nodeExecutionId, Integer attemptNo) {
        int updated = nodeExecutionMapper.markWaitingHuman(nodeExecutionId, attemptNo);
        if (updated != 1) {
            return false;
        }
        NodeExecution node = nodeExecutionMapper.selectById(nodeExecutionId);
        closeAttempt(node, attemptNo, NodeStatus.WAITING_HUMAN, null, null, null);
        eventService.append(node.getWorkflowExecutionId(), nodeExecutionId,
                EventType.NODE_WAITING_HUMAN,
                Map.of("nodeKey", node.getNodeKey(), "awaitingApproval", true), null);
        return true;
    }

    /**
     * Completes a node that waited for a human decision.
     *
     * <p>The attempt that was waiting for the decision is the attempt that finishes the node, so
     * the attempt history stays complete.
     */
    @Transactional
    public boolean completeFromHumanWait(Long nodeExecutionId, Map<String, Object> output) {
        NodeExecution node = nodeExecutionMapper.selectById(nodeExecutionId);
        if (node == null) {
            return false;
        }
        String outputJson = JsonUtils.toJson(output == null ? Map.of() : output);
        int updated = nodeExecutionMapper.completeFromHumanWait(nodeExecutionId, outputJson);
        if (updated != 1) {
            log.warn("the human node {} could not be completed", nodeExecutionId);
            return false;
        }
        int attemptNo = node.getCurrentAttempt() == null ? 1 : node.getCurrentAttempt();
        closeAttempt(node, attemptNo, NodeStatus.SUCCEEDED, outputJson, null, null);
        eventService.append(node.getWorkflowExecutionId(), nodeExecutionId,
                EventType.NODE_SUCCEEDED,
                Map.of("nodeKey", node.getNodeKey(), "approved", true), null);
        return true;
    }

    /**
     * Fails a node that waited for a human decision, used when the reviewer rejected it.
     */
    @Transactional
    public boolean failFromHumanWait(Long nodeExecutionId, String errorCode, String errorMessage) {
        NodeExecution node = nodeExecutionMapper.selectById(nodeExecutionId);
        if (node == null) {
            return false;
        }
        int updated = nodeExecutionMapper.markFailedFromHumanWait(nodeExecutionId,
                errorCode, errorMessage);
        if (updated != 1) {
            log.warn("the human node {} could not be rejected", nodeExecutionId);
            return false;
        }
        int attemptNo = node.getCurrentAttempt() == null ? 1 : node.getCurrentAttempt();
        closeAttempt(node, attemptNo, NodeStatus.FAILED, null, errorCode, errorMessage);
        eventService.append(node.getWorkflowExecutionId(), nodeExecutionId,
                EventType.NODE_FAILED,
                Map.of("nodeKey", node.getNodeKey(), "errorCode", errorCode), null);
        return true;
    }

    /**
     * Writes the attempt record. Every physical attempt is kept so the retry history survives.
     */
    private void closeAttempt(NodeExecution node, Integer attemptNo, NodeStatus status,
                              String outputJson, String errorCode, String errorMessage) {
        if (node == null) {
            return;
        }
        NodeExecutionAttempt attempt = attemptMapper.findByNodeAndAttempt(node.getId(), attemptNo);
        LocalDateTime finishedAt = LocalDateTime.now();
        Long durationMs = attempt == null || attempt.getStartedAt() == null
                ? null : java.time.Duration.between(attempt.getStartedAt(), finishedAt).toMillis();

        if (attempt == null) {
            attempt = NodeExecutionAttempt.builder()
                    .id(idGenerator.nextId())
                    .nodeExecutionId(node.getId())
                    .attemptNo(attemptNo)
                    .createdAt(LocalDateTime.now())
                    .build();
        }
        attempt.setStatus(status.name());
        attempt.setOutputSnapshot(outputJson);
        attempt.setErrorCode(errorCode);
        attempt.setErrorMessage(errorMessage);
        attempt.setFinishedAt(finishedAt);
        attempt.setDurationMs(durationMs);
        attempt.setWorkerId(node.getWorkerId());
        attempt.setLeaseToken(node.getLeaseToken() == null ? 0L : node.getLeaseToken());
        if (attempt.getStartedAt() == null) {
            attempt.setStartedAt(finishedAt);
        }
        if (attemptMapper.findByNodeAndAttempt(node.getId(), attemptNo) == null) {
            attemptMapper.insert(attempt);
        } else {
            attemptMapper.updateById(attempt);
        }
    }

    private RetryPolicy toPolicy(RetryPolicyDefinition definition) {
        if (definition == null) {
            return RetryPolicy.defaultPolicy();
        }
        return RetryPolicy.builder()
                .maxAttempts(definition.getMaxAttempts())
                .initialDelaySeconds(definition.getInitialDelaySeconds())
                .maxDelaySeconds(definition.getMaxDelaySeconds())
                .multiplier(definition.getMultiplier())
                .build();
    }
}
