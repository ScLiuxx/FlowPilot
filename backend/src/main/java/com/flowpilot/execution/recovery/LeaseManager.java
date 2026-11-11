package com.flowpilot.execution.recovery;

import com.flowpilot.execution.entity.NodeExecution;
import com.flowpilot.execution.mapper.NodeExecutionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Manages the execution lease of a node.
 *
 * <p>A lease has two purposes. It tells the system when a worker probably died, and it carries a
 * fencing token that stops a worker which lost its lease from committing a stale result.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LeaseManager {

    private final NodeExecutionMapper nodeExecutionMapper;

    @Value("${flowpilot.worker.lease-seconds:60}")
    private long leaseSeconds;

    /**
     * Attempts to acquire the execution right for a node.
     *
     * @param expectedAttempt the attempt the caller believes it is running
     * @return the new fencing token, or {@code null} when the lease could not be acquired
     */
    public Long acquire(NodeExecution node, String workerId, Integer attemptNo, Integer expectedAttempt) {
        LocalDateTime expireAt = LocalDateTime.now().plusSeconds(leaseSeconds);
        int updated = nodeExecutionMapper.acquireLease(node.getId(), workerId, attemptNo,
                expectedAttempt, expireAt);
        if (updated != 1) {
            log.debug("lease acquisition rejected for node {} attempt {} status {}",
                    node.getId(), attemptNo, node.getStatus());
            return null;
        }
        // The token is incremented by the update, so read it back.
        NodeExecution refreshed = nodeExecutionMapper.selectById(node.getId());
        Long token = refreshed == null ? null : refreshed.getLeaseToken();
        log.debug("lease acquired node={} worker={} attempt={} token={}",
                node.getId(), workerId, attemptNo, token);
        return token;
    }

    /**
     * Extends the lease.
     *
     * @return false when the worker lost the lease and must stop its work
     */
    public boolean heartbeat(NodeExecution node, String workerId, Long leaseToken) {
        LocalDateTime expireAt = LocalDateTime.now().plusSeconds(leaseSeconds);
        int updated = nodeExecutionMapper.heartbeat(node.getId(), workerId, leaseToken, expireAt);
        if (updated != 1) {
            log.warn("lease lost for node {} worker={} token={}", node.getId(), workerId, leaseToken);
            return false;
        }
        return true;
    }

    /**
     * Verifies that the caller still holds the lease.
     */
    public boolean holdsLease(Long nodeExecutionId, String workerId, Long leaseToken) {
        NodeExecution node = nodeExecutionMapper.selectById(nodeExecutionId);
        if (node == null) {
            return false;
        }
        return workerId.equals(node.getWorkerId())
                && leaseToken != null
                && leaseToken.equals(node.getLeaseToken())
                && "RUNNING".equals(node.getStatus());
    }

    public Duration leaseDuration() {
        return Duration.ofSeconds(leaseSeconds);
    }

    public long leaseSeconds() {
        return leaseSeconds;
    }
}
