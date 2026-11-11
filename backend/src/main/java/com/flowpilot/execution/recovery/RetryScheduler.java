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
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Releases nodes whose retry delay elapsed.
 *
 * <p>The delay is persisted instead of being slept on in a worker thread, so a worker never
 * blocks while waiting to retry. A node is only released when its {@code next_retry_at} is due.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetryScheduler {

    private final NodeExecutionMapper nodeExecutionMapper;
    private final WorkflowScheduler scheduler;

    @Value("${flowpilot.retry.batch-size:50}")
    private int batchSize;

    @Value("${flowpilot.retry.enabled:true}")
    private boolean enabled;

    @Scheduled(fixedDelayString = "${flowpilot.retry.interval-ms:5000}")
    public void releaseRetries() {
        if (!enabled) {
            return;
        }
        List<NodeExecution> waiting = nodeExecutionMapper.findRetryWaiting(LocalDateTime.now(), batchSize);
        for (NodeExecution node : waiting) {
            try {
                release(node);
            } catch (Exception ex) {
                log.error("failed to release the retry of node {}", node.getId(), ex);
            }
        }
    }

    /**
     * Releases one node for its next attempt.
     *
     * <p>The call goes through the scheduler, because the scheduler owns the transaction that
     * moves the node back into the ready state and writes the execution message.
     */
    public void release(NodeExecution node) {
        NodeExecution current = nodeExecutionMapper.selectById(node.getId());
        if (current == null || !NodeStatus.RETRY_WAIT.name().equals(current.getStatus())) {
            return;
        }
        if (current.getNextRetryAt() != null && current.getNextRetryAt().isAfter(LocalDateTime.now())) {
            // The delay of the retry policy has not elapsed yet.
            return;
        }
        scheduler.scheduleRetry(current);
        log.info("node {} released for attempt {}", current.getNodeKey(),
                (current.getCurrentAttempt() == null ? 0 : current.getCurrentAttempt()) + 1);
    }
}
