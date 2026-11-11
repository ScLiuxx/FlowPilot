package com.flowpilot.approval.controller;

import com.flowpilot.approval.entity.ApprovalRecord;
import com.flowpilot.approval.service.ApprovalService;
import com.flowpilot.common.result.PageResult;
import com.flowpilot.common.result.Result;
import com.flowpilot.common.security.CurrentUser;
import com.flowpilot.common.util.Ids;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/approvals")
@RequiredArgsConstructor
public class ApprovalController {

    private final ApprovalService approvalService;

    @GetMapping
    public Result<PageResult<Map<String, Object>>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {
        // A user only sees the approvals of their own executions.
        Long userId = CurrentUser.isAdmin() ? null : CurrentUser.requireId();
        PageResult<ApprovalRecord> result = approvalService.page(page, size,
                status == null || status.isBlank() ? "PENDING" : status, userId);
        List<Map<String, Object>> records = result.getRecords().stream()
                .map(ApprovalController::toResponse)
                .toList();
        return Result.success(PageResult.of(result.getTotal(), result.getPage(), result.getSize(), records));
    }

    @GetMapping("/{approvalId}")
    public Result<Map<String, Object>> get(@PathVariable Long approvalId) {
        return Result.success(toResponse(approvalService.get(approvalId)));
    }

    @PostMapping("/{approvalId}/decision")
    public Result<Map<String, Object>> decide(@PathVariable Long approvalId,
                                               @Valid @RequestBody DecisionRequest request) {
        ApprovalRecord approval = approvalService.decide(approvalId, request.getDecision(),
                request.getComment(), CurrentUser.requireId());
        return Result.success(toResponse(approval));
    }

    static Map<String, Object> toResponse(ApprovalRecord approval) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("approvalId", Ids.of(approval.getId()));
        response.put("workflowExecutionId", Ids.of(approval.getWorkflowExecutionId()));
        response.put("nodeExecutionId", Ids.of(approval.getNodeExecutionId()));
        response.put("approvalType", approval.getApprovalType());
        response.put("status", approval.getStatus());
        response.put("title", approval.getTitle());
        response.put("description", approval.getDescription());
        response.put("requestData", com.flowpilot.common.util.JsonUtils.toMap(approval.getRequestData()));
        response.put("decisionData", approval.getDecisionData() == null
                ? null : com.flowpilot.common.util.JsonUtils.toMap(approval.getDecisionData()));
        response.put("requestedAt", approval.getRequestedAt() == null
                ? null : approval.getRequestedAt().toString());
        response.put("decidedAt", approval.getDecidedAt() == null
                ? null : approval.getDecidedAt().toString());
        response.put("expireAt", approval.getExpireAt() == null
                ? null : approval.getExpireAt().toString());
        response.put("timeoutAction", approval.getTimeoutAction());
        response.put("approverId", Ids.of(approval.getApproverId()));
        response.put("comment", approval.getComment());
        return response;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DecisionRequest {
        @NotBlank
        private String decision;

        private String comment;
    }
}
