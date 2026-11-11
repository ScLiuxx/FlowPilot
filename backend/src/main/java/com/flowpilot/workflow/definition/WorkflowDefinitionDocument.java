package com.flowpilot.workflow.definition;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * The JSON document that describes a workflow DAG.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkflowDefinitionDocument {

    private String workflowId;
    private String name;
    private String description;

    @Builder.Default
    private List<WorkflowNodeDefinition> nodes = new ArrayList<>();

    public List<WorkflowNodeDefinition> nodes() {
        return nodes == null ? List.of() : nodes;
    }
}
