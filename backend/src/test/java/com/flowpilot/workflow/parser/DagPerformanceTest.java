package com.flowpilot.workflow.parser;

import com.flowpilot.workflow.definition.WorkflowDefinitionDocument;
import com.flowpilot.workflow.definition.WorkflowNodeDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Performance tests of the DAG handling.
 *
 * <p>The engine design asks for a benchmark of the validation and the topological sort at
 * different graph sizes, so a regression shows up as a failing test instead of as a slow
 * console. The budgets are deliberately generous: they catch an algorithm that no longer
 * scales, not a machine that is momentarily busy.
 */
class DagPerformanceTest {

    /** The validation of a graph of this size may not take longer than this. */
    private static final long VALIDATION_BUDGET_MILLIS = 3000;

    @Test
    @DisplayName("the validation of a large graph stays inside the budget")
    void validationOfALargeGraph() {
        WorkflowDefinitionDocument document = chain(500);

        long start = System.nanoTime();
        WorkflowValidator.ValidationResult result = WorkflowValidator.validate(document);
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertThat(result.isValid()).isTrue();
        assertThat(result.getTopologicalOrder()).hasSize(500);
        assertThat(millis)
                .as("the validation of 500 nodes took %d ms", millis)
                .isLessThan(VALIDATION_BUDGET_MILLIS);
    }

    @Test
    @DisplayName("the topological sort of a large graph stays inside the budget")
    void topologicalSortOfALargeGraph() {
        WorkflowDefinitionDocument document = chain(500);
        Map<String, WorkflowNodeDefinition> nodes = new java.util.LinkedHashMap<>();
        document.nodes().forEach(node -> nodes.put(node.getId(), node));

        long start = System.nanoTime();
        List<String> order = TopologicalSorter.sort(nodes);
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertThat(order).hasSize(500);
        assertThat(millis)
                .as("the topological sort of 500 nodes took %d ms", millis)
                .isLessThan(VALIDATION_BUDGET_MILLIS);
    }

    @Test
    @DisplayName("the validation of a wide graph with many branches stays inside the budget")
    void validationOfAWideGraph() {
        // 50 branches with 20 nodes each, so the graph has 1000 nodes and 50 roots.
        List<WorkflowNodeDefinition> nodes = new ArrayList<>();
        for (int branch = 0; branch < 50; branch++) {
            String previous = null;
            for (int step = 0; step < 20; step++) {
                String id = "b" + branch + "_" + step;
                nodes.add(node(id, "CODE", Map.of("operation", "identity"),
                        previous == null ? List.of() : List.of(previous)));
                previous = id;
            }
        }

        long start = System.nanoTime();
        WorkflowValidator.ValidationResult result = WorkflowValidator.validate(
                WorkflowDefinitionDocument.builder().name("wide").nodes(nodes).build());
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertThat(result.isValid()).isTrue();
        assertThat(result.getTopologicalOrder()).hasSize(1000);
        assertThat(millis)
                .as("the validation of 1000 nodes took %d ms", millis)
                .isLessThan(VALIDATION_BUDGET_MILLIS);
    }

    @Test
    @DisplayName("a cycle in a large graph is detected")
    void cycleInALargeGraph() {
        List<WorkflowNodeDefinition> nodes = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            nodes.add(node("n" + i, "CODE", Map.of("operation", "identity"),
                    List.of("n" + (i + 1))));
        }
        // The last node points back to the first one, so the graph has a cycle.
        nodes.add(node("n300", "CODE", Map.of("operation", "identity"), List.of("n0")));

        WorkflowValidator.ValidationResult result = WorkflowValidator.validate(
                WorkflowDefinitionDocument.builder().name("cyclic").nodes(nodes).build());

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(error -> error.contains("Cycle detected"));
    }

    /**
     * A graph of nodes that depend on their predecessor.
     */
    private WorkflowDefinitionDocument chain(int size) {
        List<WorkflowNodeDefinition> nodes = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            nodes.add(node("n" + i, "CODE", Map.of("operation", "identity"),
                    i == 0 ? List.of() : List.of("n" + (i - 1))));
        }
        return WorkflowDefinitionDocument.builder().name("chain").nodes(nodes).build();
    }

    private WorkflowNodeDefinition node(String id, String type, Map<String, Object> config,
                                        List<String> dependencies) {
        return WorkflowNodeDefinition.builder()
                .id(id).type(type).config(config).dependencies(dependencies).build();
    }
}
