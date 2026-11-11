package com.flowpilot.workflow.definition;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A single node in the workflow definition.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkflowNodeDefinition {

    /** Unique node key inside the workflow. */
    private String id;

    /** One of HTTP, CODE, CONDITION, LLM, AGENT, HUMAN, MERGE. */
    private String type;

    private String name;

    /** Node keys this node depends on. */
    @Builder.Default
    private List<String> dependencies = new ArrayList<>();

    /** Type specific configuration. */
    @Builder.Default
    private Map<String, Object> config = new LinkedHashMap<>();

    private RetryPolicyDefinition retryPolicy;

    private TimeoutPolicyDefinition timeoutPolicy;

    /** Optional explicit ordering hint for the console. */
    private Integer order;

    public List<String> dependencies() {
        return dependencies == null ? List.of() : dependencies;
    }

    public Map<String, Object> config() {
        return config == null ? Map.of() : config;
    }
}
