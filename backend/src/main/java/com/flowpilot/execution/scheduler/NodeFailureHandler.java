package com.flowpilot.execution.scheduler;

import com.flowpilot.execution.entity.NodeExecution;
import com.flowpilot.execution.mapper.NodeExecutionMapper;
import com.flowpilot.execution.model.EventType;
import com.flowpilot.execution.model.NodeStatus;
import com.flowpilot.execution.service.ExecutionEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Handles the failure of a node.
 *
 * <p>A failed node does not immediately fail the workflow. The node itself has already been moved
 * into {@code RETRY_WAIT} or {@code FAILED} by the result service. This component only checks
 * whether the workflow as a whole can still make progress.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NodeFailureHandler {

    private final NodeExecutionMapper nodeExecutionMapper;
    private final ExecutionEventService eventService;
    private final WorkflowScheduler scheduler;

    /**
     * Called when a node failed terminally.
     */
    @Transactional
    public void onNodeFailed(Long executionId, String nodeKey) {
        NodeExecution node = nodeExecutionMapper.findByExecutionAndKey(executionId, nodeKey);
        if (node == null) {
            return;
        }
        if (!NodeStatus.FAILED.name().equals(node.getStatus())) {
            // The node is only waiting for a retry, the workflow continues.
            log.debug("node {} is in state {}, the workflow keeps waiting", nodeKey, node.getStatus());
            return;
        }
        eventService.append(executionId, node.getId(), EventType.NODE_FAILED,
                Map.of("nodeKey", nodeKey, "terminal", true), null);
        scheduler.checkCompletion(executionId);
    }
}
