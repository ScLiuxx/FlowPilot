package com.flowpilot.execution.controller;

import com.flowpilot.agent.service.AgentInvocationService;
import com.flowpilot.approval.service.ApprovalService;
import com.flowpilot.common.exception.BizException;
import com.flowpilot.common.result.ErrorCode;
import com.flowpilot.common.result.PageResult;
import com.flowpilot.common.result.Result;
import com.flowpilot.common.security.CurrentUser;
import com.flowpilot.common.trace.mapper.TraceSpanMapper;
import com.flowpilot.common.util.Ids;
import com.flowpilot.common.util.JsonUtils;
import com.flowpilot.execution.dto.ExecutionDtos;
import com.flowpilot.execution.entity.ExecutionEvent;
import com.flowpilot.execution.entity.NodeExecution;
import com.flowpilot.execution.entity.NodeExecutionAttempt;
import com.flowpilot.execution.entity.WorkflowExecution;
import com.flowpilot.execution.mapper.NodeExecutionAttemptMapper;
import com.flowpilot.execution.mapper.NodeExecutionMapper;
import com.flowpilot.execution.mapper.WorkflowExecutionMapper;
import com.flowpilot.execution.model.EventType;
import com.flowpilot.execution.model.NodeStatus;
import com.flowpilot.execution.model.WorkflowStatus;
import com.flowpilot.execution.service.ExecutionEventService;
import com.flowpilot.execution.service.WorkflowExecutionService;
import com.flowpilot.monitoring.sse.SseEventBroadcaster;
import com.flowpilot.report.entity.AnalysisReport;
import com.flowpilot.report.service.ReportService;
import com.flowpilot.workflow.entity.WorkflowVersion;
import com.flowpilot.workflow.service.WorkflowService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ExecutionController {

    private final WorkflowService workflowService;
    private final WorkflowExecutionService executionService;
    private final NodeExecutionMapper nodeExecutionMapper;
    private final NodeExecutionAttemptMapper attemptMapper;
    private final WorkflowExecutionMapper executionMapper;
    private final ExecutionEventService eventService;
    private final SseEventBroadcaster sseBroadcaster;
    private final ApprovalService approvalService;
    private final AgentInvocationService agentInvocationService;
    private final ReportService reportService;
    private final TraceSpanMapper traceSpanMapper;

    /**
     * Starts a workflow execution. The call returns as soon as the execution is created.
     */
    @PostMapping("/workflows/{workflowId}/executions")
    public Result<ExecutionDtos.StartExecutionResponse> start(
            @PathVariable Long workflowId,
            @Valid @RequestBody ExecutionDtos.StartExecutionRequest request) {
        Long userId = CurrentUser.requireId();
        // Only the owner of a workflow may run it. The workflows of the platform are visible to
        // everybody and can be run by everybody.
        workflowService.requireOwned(workflowId, userId);
        WorkflowVersion version = request.getWorkflowVersionId() == null
                ? workflowService.getPublishedVersion(workflowId)
                : workflowService.getVersion(workflowId, request.getWorkflowVersionId(), userId);
        if (!"PUBLISHED".equals(version.getStatus())) {
            throw new BizException(ErrorCode.WF_VERSION_NOT_PUBLISHED);
        }
        var document = workflowService.parse(version.getDefinitionJson());
        WorkflowExecution execution = executionService.start(version, document, userId,
                request.getInput(), request.getIdempotencyKey());
        return Result.success(ExecutionDtos.StartExecutionResponse.builder()
                .executionId(Ids.of(execution.getId()))
                .executionNo(execution.getExecutionNo())
                .status(execution.getStatus())
                .build());
    }

    @GetMapping("/executions")
    public Result<PageResult<ExecutionDtos.ExecutionResponse>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long workflowId) {
        Long userId = CurrentUser.requireId();
        boolean admin = CurrentUser.isAdmin();
        PageResult<WorkflowExecution> result =
                executionService.page(page, size, status, workflowId, userId, admin);
        List<ExecutionDtos.ExecutionResponse> records = result.getRecords().stream()
                .map(ExecutionController::toResponse)
                .toList();
        return Result.success(PageResult.of(result.getTotal(), result.getPage(), result.getSize(), records));
    }

    @GetMapping("/executions/{executionId}")
    public Result<ExecutionDtos.ExecutionResponse> get(@PathVariable Long executionId) {
        WorkflowExecution execution = executionService.requireAccessible(executionId,
                CurrentUser.requireId(), CurrentUser.isAdmin());
        return Result.success(toResponse(execution));
    }

    @GetMapping("/executions/{executionId}/nodes")
    public Result<List<ExecutionDtos.NodeResponse>> nodes(@PathVariable Long executionId) {
        executionService.requireAccessible(executionId, CurrentUser.requireId(), CurrentUser.isAdmin());
        List<ExecutionDtos.NodeResponse> nodes = executionService.nodes(executionId).stream()
                .map(ExecutionController::toResponse)
                .toList();
        return Result.success(nodes);
    }

    @GetMapping("/executions/{executionId}/nodes/{nodeExecutionId}/attempts")
    public Result<List<ExecutionDtos.AttemptResponse>> attempts(@PathVariable Long executionId,
                                                                 @PathVariable Long nodeExecutionId) {
        executionService.requireAccessible(executionId, CurrentUser.requireId(), CurrentUser.isAdmin());
        // The node has to belong to the execution. Without the check a user could name the node
        // of somebody else's execution and read its attempts, because the attempts are looked up
        // by the node alone.
        if (nodeExecutionMapper.findByIdAndExecutionId(nodeExecutionId, executionId) == null) {
            throw new BizException(ErrorCode.EX_FORBIDDEN);
        }
        List<ExecutionDtos.AttemptResponse> attempts = attemptMapper.findByNodeExecutionId(nodeExecutionId)
                .stream()
                .map(ExecutionController::toResponse)
                .toList();
        return Result.success(attempts);
    }

    @GetMapping("/executions/{executionId}/events")
    public Result<List<ExecutionDtos.EventResponse>> events(
            @PathVariable Long executionId,
            @RequestParam(defaultValue = "0") long afterSequence,
            @RequestParam(defaultValue = "100") int limit) {
        executionService.requireAccessible(executionId, CurrentUser.requireId(), CurrentUser.isAdmin());
        List<ExecutionDtos.EventResponse> events = eventService
                .listAfter(executionId, afterSequence, Math.min(limit, 500)).stream()
                .map(ExecutionController::toResponse)
                .toList();
        return Result.success(events);
    }

    /**
     * Subscribes to the execution events.
     *
     * <p>The browser EventSource cannot set a header, so the token may also be passed as a query
     * parameter. The {@code Last-Event-ID} header is honoured so a reconnecting client resumes
     * where it left off.
     */
    @GetMapping(value = "/executions/{executionId}/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable Long executionId,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
                             @RequestParam(value = "lastEventId", required = false) Long lastEventIdParam) {
        executionService.requireAccessible(executionId, CurrentUser.requireId(), CurrentUser.isAdmin());
        Long afterSequence = 0L;
        if (lastEventId != null && !lastEventId.isBlank()) {
            try {
                afterSequence = Long.parseLong(lastEventId.trim());
            } catch (NumberFormatException ex) {
                afterSequence = 0L;
            }
        }
        if (lastEventIdParam != null) {
            afterSequence = lastEventIdParam;
        }
        return sseBroadcaster.subscribe(executionId, afterSequence);
    }

    /**
     * The trace of an execution.
     *
     * <p>The spans are the nodes of the execution chain, so the console can show how long every
     * step took and where the chain was interrupted.
     */
    @GetMapping("/executions/{executionId}/trace")
    public Result<Map<String, Object>> trace(@PathVariable Long executionId) {
        WorkflowExecution execution = executionService.requireAccessible(executionId,
                CurrentUser.requireId(), CurrentUser.isAdmin());
        List<Map<String, Object>> spans = traceSpanMapper.findByWorkflowExecutionId(executionId)
                .stream()
                .map(span -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("traceId", span.getTraceId());
                    item.put("spanId", span.getSpanId());
                    item.put("parentSpanId", span.getParentSpanId());
                    item.put("spanName", span.getSpanName());
                    item.put("nodeExecutionId", Ids.of(span.getNodeExecutionId()));
                    item.put("durationMs", span.getDurationMs());
                    item.put("status", span.getStatus());
                    item.put("attributes", JsonUtils.toMap(span.getAttributes()));
                    item.put("startedAt", span.getStartedAt() == null
                            ? null : span.getStartedAt().toString());
                    return item;
                })
                .toList();
        return Result.success(Map.of(
                "executionId", Ids.of(executionId),
                "traceId", execution.getTraceId() == null ? "" : execution.getTraceId(),
                "spans", spans));
    }

    /**
     * Requests the cancellation of an execution.
     */
    @PostMapping("/executions/{executionId}/cancel")
    public Result<ExecutionDtos.CancelResponse> cancel(@PathVariable Long executionId,
                                                        @RequestBody(required = false)
                                                        ExecutionDtos.CancelRequest request) {
        Long userId = CurrentUser.requireId();
        executionService.requireAccessible(executionId, userId, CurrentUser.isAdmin());
        String reason = request == null || request.getReason() == null ? "USER_REQUEST" : request.getReason();

        executionService.cancel(executionId, reason, userId);

        return Result.success(ExecutionDtos.CancelResponse.builder()
                .executionId(Ids.of(executionId))
                .status("CANCEL_REQUESTED")
                .build());
    }

    /**
     * Generates the report of an execution.
     */
    @PostMapping("/executions/{executionId}/report")
    public Result<Map<String, Object>> generateReport(@PathVariable Long executionId) {
        executionService.requireAccessible(executionId, CurrentUser.requireId(), CurrentUser.isAdmin());
        AnalysisReport report = reportService.generate(executionId);
        return Result.success(Map.of(
                "reportId", Ids.of(report.getId()),
                "reportNo", report.getReportNo(),
                "status", report.getStatus(),
                "title", report.getTitle()));
    }

    static ExecutionDtos.ExecutionResponse toResponse(WorkflowExecution execution) {
        return ExecutionDtos.ExecutionResponse.builder()
                .executionId(Ids.of(execution.getId()))
                .executionNo(execution.getExecutionNo())
                .workflowId(Ids.of(execution.getWorkflowId()))
                .workflowVersionId(Ids.of(execution.getWorkflowVersionId()))
                .userId(Ids.of(execution.getUserId()))
                .status(execution.getStatus())
                .input(JsonUtils.toMap(execution.getInputJson()))
                .output(execution.getOutputJson() == null
                        ? null : JsonUtils.toMap(execution.getOutputJson()))
                .totalNodes(execution.getCurrentNodeCount())
                .completedNodes(execution.getCompletedNodeCount())
                .failedNodes(execution.getFailedNodeCount())
                .errorCode(execution.getErrorCode())
                .errorMessage(execution.getErrorMessage())
                .startedAt(execution.getStartedAt() == null ? null : execution.getStartedAt().toString())
                .finishedAt(execution.getFinishedAt() == null ? null : execution.getFinishedAt().toString())
                .createdAt(execution.getCreatedAt() == null ? null : execution.getCreatedAt().toString())
                .build();
    }

    static ExecutionDtos.NodeResponse toResponse(NodeExecution node) {
        Long duration = null;
        if (node.getStartedAt() != null && node.getFinishedAt() != null) {
            duration = Duration.between(node.getStartedAt(), node.getFinishedAt()).toMillis();
        }
        return ExecutionDtos.NodeResponse.builder()
                .nodeExecutionId(Ids.of(node.getId()))
                .nodeKey(node.getNodeKey())
                .nodeType(node.getNodeType())
                .status(node.getStatus())
                .attempt(node.getCurrentAttempt())
                .maxAttempts(node.getMaxAttempts())
                .workerId(node.getWorkerId())
                .startedAt(node.getStartedAt() == null ? null : node.getStartedAt().toString())
                .finishedAt(node.getFinishedAt() == null ? null : node.getFinishedAt().toString())
                .durationMs(duration)
                .errorCode(node.getErrorCode())
                .errorMessage(node.getErrorMessage())
                .build();
    }

    static ExecutionDtos.AttemptResponse toResponse(NodeExecutionAttempt attempt) {
        return ExecutionDtos.AttemptResponse.builder()
                .attemptNo(attempt.getAttemptNo())
                .status(attempt.getStatus())
                .workerId(attempt.getWorkerId())
                .errorCode(attempt.getErrorCode())
                .errorMessage(attempt.getErrorMessage())
                .startedAt(attempt.getStartedAt() == null ? null : attempt.getStartedAt().toString())
                .finishedAt(attempt.getFinishedAt() == null ? null : attempt.getFinishedAt().toString())
                .durationMs(attempt.getDurationMs())
                .build();
    }

    static ExecutionDtos.EventResponse toResponse(ExecutionEvent event) {
        return ExecutionDtos.EventResponse.builder()
                .sequenceNo(event.getSequenceNo())
                .eventType(event.getEventType())
                .nodeExecutionId(Ids.of(event.getNodeExecutionId()))
                .eventData(JsonUtils.toMap(event.getEventData()))
                .createdAt(event.getCreatedAt() == null ? null : event.getCreatedAt().toString())
                .build();
    }
}
