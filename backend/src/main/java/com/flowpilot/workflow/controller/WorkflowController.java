package com.flowpilot.workflow.controller;

import com.flowpilot.common.result.PageResult;
import com.flowpilot.common.result.Result;
import com.flowpilot.common.security.CurrentUser;
import com.flowpilot.common.util.Ids;
import com.flowpilot.common.util.JsonUtils;
import com.flowpilot.workflow.definition.WorkflowDefinitionDocument;
import com.flowpilot.workflow.dto.WorkflowDtos;
import com.flowpilot.workflow.entity.WorkflowDefinition;
import com.flowpilot.workflow.entity.WorkflowVersion;
import com.flowpilot.workflow.parser.WorkflowValidator;
import com.flowpilot.workflow.service.WorkflowService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/workflows")
@RequiredArgsConstructor
@Slf4j
public class WorkflowController {

    private final WorkflowService workflowService;

    @GetMapping
    public Result<PageResult<WorkflowDtos.WorkflowResponse>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        Long ownerId = CurrentUser.isAdmin() ? null : CurrentUser.requireId();
        PageResult<WorkflowDefinition> result = workflowService.page(page, size, status, keyword, ownerId);
        List<WorkflowDtos.WorkflowResponse> records = result.getRecords().stream()
                .map(WorkflowController::toResponse)
                .toList();
        return Result.success(PageResult.of(result.getTotal(), result.getPage(), result.getSize(), records));
    }

    @PostMapping
    public Result<WorkflowDtos.WorkflowResponse> create(
            @Valid @RequestBody WorkflowDtos.CreateWorkflowRequest request) {
        WorkflowDefinition definition = workflowService.create(
                request.getName(), request.getDescription(), request.getCategory(),
                CurrentUser.requireId(), request.getDefinition());
        return Result.success(toResponse(definition));
    }

    @GetMapping("/{workflowId}")
    public Result<WorkflowDtos.WorkflowResponse> get(@PathVariable Long workflowId) {
        WorkflowDefinition definition = workflowService.requireOwned(workflowId, CurrentUser.requireId());
        return Result.success(toResponse(definition));
    }

    @PutMapping("/{workflowId}")
    public Result<WorkflowDtos.WorkflowResponse> update(
            @PathVariable Long workflowId,
            @Valid @RequestBody WorkflowDtos.UpdateWorkflowRequest request) {
        Long operatorId = CurrentUser.requireId();
        if (request.getStatus() != null) {
            workflowService.setStatus(workflowId, operatorId, request.getStatus());
            return Result.success(toResponse(workflowService.get(workflowId)));
        }
        WorkflowDefinition definition = workflowService.update(workflowId, request.getName(),
                request.getDescription(), request.getCategory(), operatorId);
        return Result.success(toResponse(definition));
    }

    @DeleteMapping("/{workflowId}")
    public Result<Void> delete(@PathVariable Long workflowId) {
        workflowService.delete(workflowId, CurrentUser.requireId());
        return Result.success();
    }

    @GetMapping("/{workflowId}/versions")
    public Result<List<WorkflowDtos.VersionResponse>> versions(@PathVariable Long workflowId) {
        return Result.success(workflowService.listVersions(workflowId).stream()
                .map(WorkflowController::toResponse)
                .toList());
    }

    @GetMapping("/{workflowId}/versions/{versionId}")
    public Result<WorkflowDtos.VersionResponse> version(@PathVariable Long workflowId,
                                                       @PathVariable Long versionId) {
        return Result.success(toResponse(workflowService.getVersion(workflowId, versionId)));
    }

    @PostMapping("/{workflowId}/versions")
    public Result<WorkflowDtos.VersionResponse> createVersion(
            @PathVariable Long workflowId,
            @Valid @RequestBody WorkflowDtos.CreateVersionRequest request) {
        WorkflowVersion version = workflowService.createVersion(workflowId, request.getDefinition(),
                CurrentUser.requireId());
        return Result.success(toResponse(version));
    }

    @PostMapping("/{workflowId}/publish")
    public Result<WorkflowDtos.VersionResponse> publish(
            @PathVariable Long workflowId,
            @Valid @RequestBody WorkflowDtos.PublishRequest request) {
        WorkflowVersion version = workflowService.publish(workflowId, request.getVersionId(),
                CurrentUser.requireId());
        return Result.success(toResponse(version));
    }

    @PostMapping("/validate")
    public Result<WorkflowDtos.ValidationResponse> validate(
            @Valid @RequestBody WorkflowDtos.ValidateRequest request) {
        WorkflowValidator.ValidationResult result = WorkflowValidator.validate(request.getDefinition());
        return Result.success(WorkflowDtos.ValidationResponse.builder()
                .valid(result.isValid())
                .topologicalOrder(result.getTopologicalOrder())
                .errors(result.getErrors())
                .warnings(result.getWarnings())
                .build());
    }

    static WorkflowDtos.WorkflowResponse toResponse(WorkflowDefinition definition) {
        return WorkflowDtos.WorkflowResponse.builder()
                .id(Ids.of(definition.getId()))
                .name(definition.getName())
                .description(definition.getDescription())
                .category(definition.getCategory())
                .ownerId(Ids.of(definition.getOwnerId()))
                .latestVersion(definition.getLatestVersion())
                .status(definition.getStatus())
                .createdAt(definition.getCreatedAt() == null ? null : definition.getCreatedAt().toString())
                .updatedAt(definition.getUpdatedAt() == null ? null : definition.getUpdatedAt().toString())
                .build();
    }

    static WorkflowDtos.VersionResponse toResponse(WorkflowVersion version) {
        return WorkflowDtos.VersionResponse.builder()
                .id(Ids.of(version.getId()))
                .workflowId(Ids.of(version.getWorkflowId()))
                .version(version.getVersion())
                .status(version.getStatus())
                .definitionHash(version.getDefinitionHash())
                .definitionJson(version.getDefinitionJson())
                .definition(parseDefinition(version.getDefinitionJson()))
                .createdBy(Ids.of(version.getCreatedBy()))
                .publishedAt(version.getPublishedAt() == null ? null : version.getPublishedAt().toString())
                .createdAt(version.getCreatedAt() == null ? null : version.getCreatedAt().toString())
                .build();
    }

    /**
     * Parses the canonical definition of a version, so the console can render the nodes without
     * having to parse the json itself.
     */
    private static WorkflowDefinitionDocument parseDefinition(String definitionJson) {
        if (definitionJson == null || definitionJson.isBlank()) {
            return null;
        }
        try {
            return JsonUtils.fromJson(definitionJson, WorkflowDefinitionDocument.class);
        } catch (RuntimeException ex) {
            log.warn("the definition of a workflow version could not be parsed: {}", ex.getMessage());
            return null;
        }
    }
}
