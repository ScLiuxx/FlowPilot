package com.flowpilot.monitoring.controller;

import com.flowpilot.agent.mapper.AgentInvocationMapper;
import com.flowpilot.approval.mapper.ApprovalRecordMapper;
import com.flowpilot.common.result.Result;
import com.flowpilot.common.security.CurrentUser;
import com.flowpilot.execution.entity.NodeExecution;
import com.flowpilot.execution.mapper.NodeExecutionMapper;
import com.flowpilot.execution.mapper.WorkflowExecutionMapper;
import com.flowpilot.monitoring.sse.SseEventBroadcaster;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Aggregated runtime statistics for the console dashboard.
 *
 * <p>The statistics belong to the executions of the user. The administrator sees the numbers of
 * everybody, a normal user only sees its own.
 */
@RestController
@RequestMapping("/api/v1/monitoring")
@RequiredArgsConstructor
public class MonitoringController {

    private final WorkflowExecutionMapper executionMapper;
    private final NodeExecutionMapper nodeExecutionMapper;
    private final ApprovalRecordMapper approvalMapper;
    private final AgentInvocationMapper invocationMapper;
    private final SseEventBroadcaster sseBroadcaster;

    @GetMapping("/overview")
    public Result<Map<String, Object>> overview() {
        Long owner = owner();
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("totalExecutions", executionMapper.countByOwner(owner, null));
        overview.put("runningExecutions", executionMapper.countByOwner(owner, "RUNNING"));
        overview.put("queuedExecutions", executionMapper.countByOwner(owner, "QUEUED"));
        overview.put("waitingHumanExecutions", executionMapper.countByOwner(owner, "WAITING_HUMAN"));
        overview.put("retryWaitExecutions", executionMapper.countByOwner(owner, "RETRY_WAIT"));
        overview.put("succeededExecutions", executionMapper.countByOwner(owner, "SUCCEEDED"));
        overview.put("failedExecutions", executionMapper.countByOwner(owner, "FAILED"));
        overview.put("canceledExecutions", executionMapper.countByOwner(owner, "CANCELED"));
        overview.put("totalNodes", nodeExecutionMapper.countByOwner(owner));
        overview.put("pendingApprovals", approvalMapper.countPendingByOwner(owner));
        overview.put("activeSseSubscribers", sseBroadcaster.subscriberCount());

        AgentInvocationMapper.TokenSum tokens = invocationMapper.sumTokensByOwner(owner);
        overview.put("totalTokenInput", tokens == null ? 0L : tokens.getTokenInput());
        overview.put("totalTokenOutput", tokens == null ? 0L : tokens.getTokenOutput());
        return Result.success(overview);
    }

    /**
     * The node execution counts grouped by status.
     */
    @GetMapping("/nodes")
    public Result<Map<String, Object>> nodeStatistics() {
        Long owner = owner();
        Map<String, Object> statistics = new LinkedHashMap<>();
        for (NodeExecution node : nodeExecutionMapper.findStatesByOwner(owner)) {
            Object current = statistics.get(node.getStatus());
            long count = current instanceof Number number ? number.longValue() : 0L;
            statistics.put(node.getStatus(), count + 1);
        }
        return Result.success(statistics);
    }

    /**
     * The owner of the statistics: the user of the request, or {@code null} for the
     * administrator, who sees the numbers of everybody.
     */
    private Long owner() {
        return CurrentUser.isAdmin() ? null : CurrentUser.requireId();
    }
}
