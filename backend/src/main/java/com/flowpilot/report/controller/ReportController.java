package com.flowpilot.report.controller;

import com.flowpilot.common.result.PageResult;
import com.flowpilot.common.result.Result;
import com.flowpilot.common.security.CurrentUser;
import com.flowpilot.common.util.Ids;
import com.flowpilot.common.util.JsonUtils;
import com.flowpilot.execution.entity.WorkflowExecution;
import com.flowpilot.execution.mapper.WorkflowExecutionMapper;
import com.flowpilot.report.entity.AnalysisReport;
import com.flowpilot.report.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;
    private final WorkflowExecutionMapper executionMapper;

    @GetMapping
    public Result<PageResult<Map<String, Object>>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {
        // A report belongs to the user of its execution. The list therefore only shows the
        // reports of the own executions, unless the caller is an administrator.
        Long userId = CurrentUser.isAdmin() ? null : CurrentUser.requireId();
        PageResult<AnalysisReport> result = reportService.page(page, size, status, userId);
        List<Map<String, Object>> records = result.getRecords().stream()
                .map(ReportController::toSummaryResponse)
                .toList();
        return Result.success(PageResult.of(result.getTotal(), result.getPage(), result.getSize(), records));
    }

    @GetMapping("/{reportId}")
    public Result<Map<String, Object>> get(@PathVariable Long reportId) {
        AnalysisReport report = reportService.get(reportId);
        requireAccess(report);
        return Result.success(toResponse(report));
    }

    @GetMapping("/{reportId}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long reportId) {
        AnalysisReport report = reportService.get(reportId);
        requireAccess(report);
        String markdown = report.getMarkdownContent() == null ? "" : report.getMarkdownContent();
        byte[] body = markdown.getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + report.getReportNo() + ".md\"")
                .contentType(MediaType.parseMediaType("text/markdown; charset=UTF-8"))
                .body(body);
    }

    private void requireAccess(AnalysisReport report) {
        WorkflowExecution execution = executionMapper.selectById(report.getWorkflowExecutionId());
        if (execution == null) {
            return;
        }
        if (!CurrentUser.isAdmin() && !execution.getUserId().equals(CurrentUser.requireId())) {
            throw new com.flowpilot.common.exception.BizException(
                    com.flowpilot.common.result.ErrorCode.RP_FORBIDDEN);
        }
    }

    static Map<String, Object> toResponse(AnalysisReport report) {
        Map<String, Object> response = toSummaryResponse(report);
        response.put("content", report.getContentJson() == null
                ? null : JsonUtils.toMap(report.getContentJson()));
        response.put("markdownContent", report.getMarkdownContent());
        return response;
    }

    /**
     * The summary of a report, without its content.
     *
     * <p>The list of the reports only shows the summary. The content and the markdown are large
     * and belong to the detail of a single report, so the list does not carry them.
     */
    static Map<String, Object> toSummaryResponse(AnalysisReport report) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("reportId", Ids.of(report.getId()));
        response.put("reportNo", report.getReportNo());
        response.put("workflowExecutionId", Ids.of(report.getWorkflowExecutionId()));
        response.put("title", report.getTitle());
        response.put("repositoryUrl", report.getRepositoryUrl());
        response.put("reportType", report.getReportType());
        response.put("status", report.getStatus());
        response.put("summary", report.getSummary());
        response.put("contentHash", report.getContentHash());
        response.put("generatedAt", report.getGeneratedAt() == null
                ? null : report.getGeneratedAt().toString());
        response.put("createdAt", report.getCreatedAt() == null
                ? null : report.getCreatedAt().toString());
        return response;
    }
}
