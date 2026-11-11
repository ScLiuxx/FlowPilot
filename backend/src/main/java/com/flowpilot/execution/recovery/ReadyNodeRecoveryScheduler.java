package com.flowpilot.execution.recovery;

import com.flowpilot.execution.entity.NodeExecution;
import com.flowpilot.execution.mapper.NodeExecutionMapper;
import com.flowpilot.execution.model.NodeStatus;
import com.flowpilot.execution.scheduler.WorkflowScheduler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Picks up the nodes that were marked ready but never reached a worker.
 *
 * <p>A node becomes ready in the same transaction that writes its execution message, so the
 * message exists. But the message can be lost: the broker accepted it, the consumer recorded
 * the deduplication mark and then the process died before the worker acquired the lease. The
 * message is not redelivered, because the deduplication mark says it was already handled, and
 * the node stays ready forever.
 *
 * <p>The scanner finds the ready nodes that have no attempt and no worker, and schedules them
 * again. The attempt number stays the same, so a node that was actually executed is not executed
 * twice: the lease of the first worker rejects the second attempt.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReadyNodeRecoveryScheduler {

    private final NodeExecutionMapper nodeExecutionMapper;
    private final WorkflowScheduler scheduler;

    @Value("${flowpilot.recovery.ready-batch-size:50}")
    private int batchSize;

    @Value("${flowpilot.recovery.ready-enabled:true}")
    private boolean enabled;

    /**
     * How long a node may stay ready before it is considered lost. The value is generous, because
     * a queue that is busy for a while is normal.
     */
    @Value("${flowpilot.recovery.ready-timeout-seconds:300}")
    private long readyTimeoutSeconds;

    @Scheduled(fixedDelayString = "${flowpilot.recovery.ready-interval-ms:30000}")
    public void rescheduleLostNodes() {
        if (!enabled) {
            return;
        }
        LocalDateTime deadline = LocalDateTime.now().minusSeconds(readyTimeoutSeconds);
        List<NodeExecution> lost = nodeExecutionMapper.findReadyWithoutWorker(deadline, batchSize);
        for (NodeExecution node : lost) {
            try {
                reschedule(node);
            } catch (Exception ex) {
                log.error("failed to reschedule the ready node {}", node.getId(), ex);
            }
        }
    }

    /**
     * Schedules a ready node again.
     *
     * <p>The node keeps its attempt number. The message is written with the same attempt, and a
     * worker that acquires the lease rejects a second attempt of the same number.
     */
    private void reschedule(NodeExecution node) {
        NodeExecution current = nodeExecutionMapper.selectById(node.getId());
        if (current == null || !NodeStatus.READY.name().equals(current.getStatus())) {
            return;
        }
        if (current.getWorkerId() != null && !current.getWorkerId().isBlank()) {
            // A worker already owns the node.
            return;
        }
        if (current.getLeaseExpireAt() != null && current.getLeaseExpireAt().isAfter(LocalDateTime.now())) {
            // The lease is still valid, so the worker is alive.
            return;
        }
        scheduler.scheduleReady(current);
        log.warn("the ready node {} was not picked up by a worker, it is scheduled again",
                current.getNodeKey());
    }
}
