package com.flowpilot.monitoring.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.flowpilot.agent.entity.AgentInvocation;
import com.flowpilot.agent.mapper.AgentInvocationMapper;
import com.flowpilot.approval.entity.ApprovalRecord;
import com.flowpilot.approval.mapper.ApprovalRecordMapper;
import com.flowpilot.common.result.Result;
import com.flowpilot.execution.entity.NodeExecution;
import com.flowpilot.execution.entity.WorkflowExecution;
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
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("totalExecutions", countExecutions(null));
        overview.put("runningExecutions", countExecutions("RUNNING"));
        overview.put("queuedExecutions", countExecutions("QUEUED"));
        overview.put("waitingHumanExecutions", countExecutions("WAITING_HUMAN"));
        overview.put("retryWaitExecutions", countExecutions("RETRY_WAIT"));
        overview.put("succeededExecutions", countExecutions("SUCCEEDED"));
        overview.put("failedExecutions", countExecutions("FAILED"));
        overview.put("canceledExecutions", countExecutions("CANCELED"));
        overview.put("totalNodes", nodeExecutionMapper.selectCount(null));
        overview.put("pendingApprovals", approvalMapper.selectCount(new LambdaQueryWrapper<ApprovalRecord>()
                .eq(ApprovalRecord::getStatus, ApprovalRecord.STATUS_PENDING)));
        overview.put("activeSseSubscribers", sseBroadcaster.subscriberCount());

        Long tokenInput = 0L;
        Long tokenOutput = 0L;
        for (AgentInvocation invocation : invocationMapper.selectList(null)) {
            tokenInput += invocation.getTokenInput() == null ? 0L : invocation.getTokenInput();
            tokenOutput += invocation.getTokenOutput() == null ? 0L : invocation.getTokenOutput();
        }
        overview.put("totalTokenInput", tokenInput);
        overview.put("totalTokenOutput", tokenOutput);
        return Result.success(overview);
    }

    private long countExecutions(String status) {
        LambdaQueryWrapper<WorkflowExecution> query = new LambdaQueryWrapper<>();
        if (status != null) {
            query.eq(WorkflowExecution::getStatus, status);
        }
        return executionMapper.selectCount(query);
    }

    /**
     * The node execution counts grouped by status.
     */
    @GetMapping("/nodes")
    public Result<Map<String, Object>> nodeStatistics() {
        Map<String, Object> statistics = new LinkedHashMap<>();
        for (NodeExecution node : nodeExecutionMapper.selectList(null)) {
            Object current = statistics.get(node.getStatus());
            long count = current instanceof Number number ? number.longValue() : 0L;
            statistics.put(node.getStatus(), count + 1);
        }
        return Result.success(statistics);
    }
}
