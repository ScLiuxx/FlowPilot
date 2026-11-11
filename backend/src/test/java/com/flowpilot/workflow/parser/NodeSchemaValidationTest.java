package com.flowpilot.workflow.parser;

import com.flowpilot.workflow.definition.WorkflowDefinitionDocument;
import com.flowpilot.workflow.definition.WorkflowNodeDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests of the extended workflow validation.
 *
 * <p>The architecture design asks for seven checks before a workflow is published: the
 * uniqueness of the node ids, the existence of the dependencies, the self dependency, the
 * circular dependency, the validity of the node types, the validity of the input and output
 * mapping and the isolation of a node.
 */
class NodeSchemaValidationTest {

    @Test
    @DisplayName("rejects a node that is isolated")
    void rejectsIsolatedNode() {
        WorkflowValidator.ValidationResult result = WorkflowValidator.validate(document(
                node("a", "CODE", Map.of("operation", "identity"), List.of()),
                node("b", "CODE", Map.of("operation", "identity"), List.of()),
                node("c", "CODE", Map.of("operation", "identity"), List.of("a"))));

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(error -> error.contains("Node b is isolated"));
    }

    @Test
    @DisplayName("accepts a workflow with a single node")
    void acceptsSingleNodeWorkflow() {
        WorkflowValidator.ValidationResult result = WorkflowValidator.validate(document(
                node("a", "CODE", Map.of("operation", "identity"), List.of())));

        assertThat(result.isValid()).isTrue();
    }

    @Test
    @DisplayName("rejects a branch that names an unknown node")
    void rejectsUnknownBranchNode() {
        WorkflowValidator.ValidationResult result = WorkflowValidator.validate(document(
                node("a", "CONDITION", Map.of("expression", "x > 1",
                        "branches", Map.of("true", List.of("ghost"))), List.of()),
                node("b", "CODE", Map.of("operation", "identity"), List.of("a"))));

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(error -> error.contains("names an unknown node"));
    }

    @Test
    @DisplayName("rejects a configuration that does not fit the schema of the node type")
    void rejectsInvalidConfiguration() {
        WorkflowValidator.ValidationResult result = WorkflowValidator.validate(document(
                node("a", "MERGE", Map.of("mode", "SOMETIMES"), List.of())));

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(error -> error.contains("invalid configuration"));
    }

    @Test
    @DisplayName("accepts a configuration that fits the schema of the node type")
    void acceptsValidConfiguration() {
        WorkflowValidator.ValidationResult result = WorkflowValidator.validate(document(
                node("a", "MERGE", Map.of("mode", "ALL"), List.of()),
                node("b", "CODE", Map.of("operation", "identity"), List.of("a"))));

        assertThat(result.isValid()).isTrue();
    }

    @Test
    @DisplayName("rejects a successor that requires a property the predecessor does not produce")
    void rejectsIncompatibleMapping() {
        WorkflowValidator.ValidationResult result = WorkflowValidator.validate(document(
                node("a", "CODE", Map.of("operation", "identity",
                        "outputSchema", Map.of("properties", Map.of("count", Map.of("type", "number")))),
                        List.of()),
                node("b", "CODE", Map.of("operation", "identity",
                        "inputSchema", Map.of("required", List.of("total"),
                                "properties", Map.of("total", Map.of("type", "number")))),
                        List.of("a"))));

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(error -> error.contains("does not produce it"));
    }

    @Test
    @DisplayName("rejects a successor that expects another type than the predecessor produces")
    void rejectsTypeMismatch() {
        WorkflowValidator.ValidationResult result = WorkflowValidator.validate(document(
                node("a", "CODE", Map.of("operation", "identity",
                        "outputSchema", Map.of("properties", Map.of("count", Map.of("type", "number")))),
                        List.of()),
                node("b", "CODE", Map.of("operation", "identity",
                        "inputSchema", Map.of("properties", Map.of("count", Map.of("type", "string")))),
                        List.of("a"))));

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(error -> error.contains("has the type number"));
    }

    @Test
    @DisplayName("accepts a compatible mapping")
    void acceptsCompatibleMapping() {
        WorkflowValidator.ValidationResult result = WorkflowValidator.validate(document(
                node("a", "CODE", Map.of("operation", "identity",
                        "outputSchema", Map.of("properties", Map.of("count", Map.of("type", "number")))),
                        List.of()),
                node("b", "CODE", Map.of("operation", "identity",
                        "inputSchema", Map.of("required", List.of("count"),
                                "properties", Map.of("count", Map.of("type", "number")))),
                        List.of("a"))));

        assertThat(result.isValid()).isTrue();
    }

    @Test
    @DisplayName("the schema of a node output is checked against the produced data")
    void validatesNodeOutput() {
        List<String> problems = NodeSchemaRegistry.validate(
                NodeSchemaRegistry.outputSchema(Map.of("outputSchema",
                        Map.of("required", List.of("count"),
                                "properties", Map.of("count", Map.of("type", "number"))))),
                Map.of("count", "not a number"));

        assertThat(problems).isNotEmpty();
    }

    @Test
    @DisplayName("a node output that fits the schema passes")
    void acceptsValidNodeOutput() {
        List<String> problems = NodeSchemaRegistry.validate(
                NodeSchemaRegistry.outputSchema(Map.of("outputSchema",
                        Map.of("required", List.of("count"),
                                "properties", Map.of("count", Map.of("type", "number"))))),
                Map.of("count", 42));

        assertThat(problems).isEmpty();
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
