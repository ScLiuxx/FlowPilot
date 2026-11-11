package com.flowpilot.execution.recovery;

import com.flowpilot.common.util.JsonUtils;
import com.flowpilot.execution.entity.NodeExecution;
import com.flowpilot.execution.entity.WorkflowExecution;
import com.flowpilot.execution.mapper.NodeExecutionMapper;
import com.flowpilot.execution.mapper.WorkflowExecutionMapper;
import com.flowpilot.execution.model.EventType;
import com.flowpilot.execution.model.NodeStatus;
import com.flowpilot.execution.model.NodeType;
import com.flowpilot.execution.model.WorkflowStatus;
import com.flowpilot.execution.scheduler.WorkflowScheduler;
import com.flowpilot.execution.service.ExecutionEventService;
import com.flowpilot.execution.service.NodeResultService;
import com.flowpilot.workflow.definition.WorkflowDefinitionDocument;
import com.flowpilot.workflow.definition.WorkflowNodeDefinition;
import com.flowpilot.workflow.entity.WorkflowVersion;
import com.flowpilot.workflow.service.WorkflowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Recovers tasks whose worker died.
 *
 * <p>A worker that dies leaves its node in the running state. The lease of that node expires,
 * and this scanner picks it up. The recovery depends on the node type:
 * <ul>
 *   <li>a normal node is retried with a new attempt,</li>
 *   <li>an agent node is redispatched with the same invocation, so the agent service can continue
 *       from its checkpoint,</li>
 *   <li>a node that waits for a human keeps waiting.</li>
 * </ul>
 *
 * <p>An expired lease does not prove that the original worker stopped. It might be a network
 * partition. The fencing token solves this: the recovering worker increments the token, and the
 * old worker can no longer commit its result.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecoveryScheduler {

    private final NodeExecutionMapper nodeExecutionMapper;
    private final WorkflowExecutionMapper executionMapper;
    private final WorkflowService workflowService;
    private final WorkflowScheduler scheduler;
    private final NodeResultService resultService;
    private final ExecutionEventService eventService;
    private final TransactionTemplate transactionTemplate;

    @Value("${flowpilot.recovery.batch-size:50}")
    private int batchSize;

    @Value("${flowpilot.recovery.enabled:true}")
    private boolean enabled;

    /**
     * Scans for nodes whose lease expired.
     */
    @Scheduled(fixedDelayString = "${flowpilot.recovery.interval-ms:15000}")
    public void recoverExpiredLeases() {
        if (!enabled) {
            return;
        }
        List<NodeExecution> expired = nodeExecutionMapper.findExpiredLeases(LocalDateTime.now(), batchSize);
        for (NodeExecution node : expired) {
            try {
                recoverNode(node);
            } catch (Exception ex) {
                log.error("failed to recover node {}", node.getId(), ex);
            }
        }
    }

    /**
     * Takes a node whose worker died over.
     *
     * <p>The re-read and the conditional update happen in the same transaction, so two recovery
     * scanners cannot take the same node over: the update only succeeds while the node is still
     * running with the expired lease.
     */
    public void recoverNode(NodeExecution node) {
        transactionTemplate.executeWithoutResult(status -> {
            // Re-read under the current transaction and make sure the node is still running with an
            // expired lease. Another scanner may have recovered it in the meantime.
            NodeExecution current = nodeExecutionMapper.selectById(node.getId());
            if (current == null || !NodeStatus.RUNNING.name().equals(current.getStatus())) {
                return;
            }
            if (current.getLeaseExpireAt() == null
                    || current.getLeaseExpireAt().isAfter(LocalDateTime.now())) {
                return;
            }

            WorkflowExecution execution = executionMapper.selectById(current.getWorkflowExecutionId());
            if (execution == null || WorkflowStatus.valueOf(execution.getStatus()).isTerminal()) {
                return;
            }

            int attemptNo = (current.getCurrentAttempt() == null ? 1 : current.getCurrentAttempt());
            NodeType nodeType = NodeType.from(current.getNodeType());

            eventService.append(current.getWorkflowExecutionId(), current.getId(), EventType.NODE_RETRY,
                    Map.of("nodeKey", current.getNodeKey(),
                            "attempt", attemptNo,
                            "reason", "LEASE_EXPIRED",
                            "previousWorker", String.valueOf(current.getWorkerId())), null);

            log.warn("node {} lease expired, worker={} attempt={} type={}",
                    current.getId(), current.getWorkerId(), attemptNo, nodeType);

            if (nodeType.isAgentBacked()) {
                // The agent node keeps its invocation, so the redispatch continues from the checkpoint.
                current.setCurrentAttempt(attemptNo - 1);
                redispatchAgent(current, execution);
                return;
            }

            // A normal node gets a new attempt. The retry policy decides whether that is allowed.
            resultService.fail(current.getId(), attemptNo, current.getLeaseToken(),
                    "WORKER_LEASE_EXPIRED", "The worker stopped sending heartbeats",
                    "TRANSIENT", true, retryPolicyOf(current, execution));
        });
    }

    /**
     * Redispatches an agent node with the same invocation, so the agent service resumes from its
     * checkpoint instead of starting over.
     */
    private void redispatchAgent(NodeExecution node, WorkflowExecution execution) {
        WorkflowDefinitionDocument document = loadDocument(execution);
        WorkflowNodeDefinition definition = null;
        for (WorkflowNodeDefinition candidate : document.nodes()) {
            if (candidate.getId().equals(node.getNodeKey())) {
                definition = candidate;
                break;
            }
        }
        if (definition == null) {
            log.warn("cannot recover agent node {}, the definition is gone", node.getId());
            return;
        }
        node.setCurrentAttempt((node.getCurrentAttempt() == null ? 1 : node.getCurrentAttempt()) - 1);
        scheduler.scheduleAgentRecovery(node, definition);
    }

    private com.flowpilot.workflow.definition.RetryPolicyDefinition retryPolicyOf(NodeExecution node,
                                                                                  WorkflowExecution execution) {
        WorkflowDefinitionDocument document = loadDocument(execution);
        for (WorkflowNodeDefinition candidate : document.nodes()) {
            if (candidate.getId().equals(node.getNodeKey())) {
                return candidate.getRetryPolicy();
            }
        }
        return null;
    }

    private WorkflowDefinitionDocument loadDocument(WorkflowExecution execution) {
        WorkflowVersion version = workflowService.getVersion(execution.getWorkflowId(),
                execution.getWorkflowVersionId());
        return workflowService.parse(version.getDefinitionJson());
    }
}
