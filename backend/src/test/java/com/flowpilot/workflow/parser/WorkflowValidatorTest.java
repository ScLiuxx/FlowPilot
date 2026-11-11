package com.flowpilot.workflow.parser;

import com.flowpilot.workflow.definition.WorkflowDefinitionDocument;
import com.flowpilot.workflow.definition.WorkflowNodeDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests of the workflow definition validation.
 */
class WorkflowValidatorTest {

    @Test
    @DisplayName("accepts a valid DAG")
    void acceptsValidDag() {
        WorkflowValidator.ValidationResult result = WorkflowValidator.validate(document(
                node("a", "CODE", Map.of("operation", "identity"), List.of()),
                node("b", "CODE", Map.of("operation", "identity"), List.of("a"))));

        assertThat(result.isValid()).isTrue();
        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getTopologicalOrder()).containsExactly("a", "b");
    }

    @Test
    @DisplayName("rejects duplicate node ids")
    void rejectsDuplicateNodeIds() {
        WorkflowValidator.ValidationResult result = WorkflowValidator.validate(document(
                node("a", "CODE", Map.of("operation", "identity"), List.of()),
                node("a", "CODE", Map.of("operation", "identity"), List.of())));

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(error -> error.contains("Duplicate node id"));
    }

    @Test
    @DisplayName("rejects a circular dependency")
    void rejectsCircularDependency() {
        WorkflowValidator.ValidationResult result = WorkflowValidator.validate(document(
                node("a", "CODE", Map.of("operation", "identity"), List.of("c")),
                node("b", "CODE", Map.of("operation", "identity"), List.of("a")),
                node("c", "CODE", Map.of("operation", "identity"), List.of("b"))));

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(error -> error.contains("Cycle detected"));
    }

    @Test
    @DisplayName("rejects a self dependency")
    void rejectsSelfDependency() {
        WorkflowValidator.ValidationResult result = WorkflowValidator.validate(document(
                node("a", "CODE", Map.of("operation", "identity"), List.of("a"))));

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(error -> error.contains("depends on itself"));
    }

    @Test
    @DisplayName("rejects a dependency on an unknown node")
    void rejectsUnknownDependency() {
        WorkflowValidator.ValidationResult result = WorkflowValidator.validate(document(
                node("a", "CODE", Map.of("operation", "identity"), List.of("ghost"))));

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(error -> error.contains("unknown node"));
    }

    @Test
    @DisplayName("rejects an unsupported node type")
    void rejectsUnsupportedNodeType() {
        WorkflowValidator.ValidationResult result = WorkflowValidator.validate(document(
                node("a", "TELEPORT", Map.of(), List.of())));

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(error -> error.contains("unsupported type"));
    }

    @Test
    @DisplayName("requires the agent type of an AGENT node")
    void requiresAgentType() {
        WorkflowValidator.ValidationResult result = WorkflowValidator.validate(document(
                node("a", "AGENT", Map.of(), List.of())));

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(error -> error.contains("config.agentType"));
    }

    @Test
    @DisplayName("requires a url or an operation for an HTTP node")
    void requiresHttpTarget() {
        WorkflowValidator.ValidationResult result = WorkflowValidator.validate(document(
                node("a", "HTTP", Map.of(), List.of())));

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(error -> error.contains("url or an operation"));
    }

    @Test
    @DisplayName("requires an approval type for a HUMAN node")
    void requiresApprovalType() {
        WorkflowValidator.ValidationResult result = WorkflowValidator.validate(document(
                node("a", "HUMAN", Map.of(), List.of())));

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(error -> error.contains("config.approvalType"));
    }

    @Test
    @DisplayName("rejects an empty workflow")
    void rejectsEmptyWorkflow() {
        WorkflowValidator.ValidationResult result = WorkflowValidator.validate(
                new WorkflowDefinitionDocument());

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(error -> error.contains("at least one node"));
    }

    @Test
    @DisplayName("accepts a graph with several independent branches")
    void acceptsIndependentBranches() {
        // a -> b and a -> c, the two branches may run in parallel.
        WorkflowValidator.ValidationResult result = WorkflowValidator.validate(document(
                node("a", "CODE", Map.of("operation", "identity"), List.of()),
                node("b", "CODE", Map.of("operation", "identity"), List.of("a")),
                node("c", "CODE", Map.of("operation", "identity"), List.of("a"))));

        assertThat(result.isValid()).isTrue();
        assertThat(result.getTopologicalOrder()).hasSize(3);
        assertThat(result.getTopologicalOrder().get(0)).isEqualTo("a");
    }

    private WorkflowDefinitionDocument document(WorkflowNodeDefinition... nodes) {
        return WorkflowDefinitionDocument.builder()
                .name("test")
                .nodes(List.of(nodes))
                .build();
    }

    private WorkflowNodeDefinition node(String id, String type, Map<String, Object> config,
                                        List<String> dependencies) {
        return WorkflowNodeDefinition.builder()
                .id(id)
                .type(type)
                .config(config)
                .dependencies(dependencies)
                .build();
    }
}
