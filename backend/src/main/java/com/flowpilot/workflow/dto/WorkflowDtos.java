package com.flowpilot.workflow.dto;

import com.flowpilot.workflow.definition.WorkflowDefinitionDocument;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

public final class WorkflowDtos {

    private WorkflowDtos() {
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateWorkflowRequest {
        @NotBlank
        @Size(max = 128)
        private String name;

        @Size(max = 512)
        private String description;

        @Size(max = 64)
        private String category;

        @NotNull
        private WorkflowDefinitionDocument definition;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateWorkflowRequest {
        @Size(max = 128)
        private String name;

        @Size(max = 512)
        private String description;

        @Size(max = 64)
        private String category;

        private String status;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateVersionRequest {
        @NotNull
        private WorkflowDefinitionDocument definition;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PublishRequest {
        @NotNull
        private Long versionId;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValidateRequest {
        @NotNull
        private WorkflowDefinitionDocument definition;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValidationResponse {
        private boolean valid;
        private List<String> topologicalOrder;
        private List<String> errors;
        private List<String> warnings;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WorkflowResponse {
        private String id;
        private String name;
        private String description;
        private String category;
        private String ownerId;
        private Integer latestVersion;
        private String status;
        private String createdAt;
        private String updatedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VersionResponse {
        private String id;
        private String workflowId;
        private Integer version;
        private String status;
        private String definitionHash;
        private String definitionJson;
        /** The parsed DAG, so the console does not have to parse the canonical json itself. */
        private WorkflowDefinitionDocument definition;
        private String createdBy;
        private String publishedAt;
        private String createdAt;
    }
}
