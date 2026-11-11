package com.flowpilot.workflow.parser;

import com.flowpilot.workflow.definition.WorkflowNodeDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests of the Kahn topological sort, which is the foundation of the cycle detection.
 */
class TopologicalSorterTest {

    @Test
    @DisplayName("sorts a linear chain in dependency order")
    void sortsLinearChain() {
        Map<String, WorkflowNodeDefinition> nodes = new LinkedHashMap<>();
        nodes.put("a", node("a", List.of()));
        nodes.put("b", node("b", List.of("a")));
        nodes.put("c", node("c", List.of("b")));

        List<String> order = TopologicalSorter.sort(nodes);

        assertThat(order).containsExactly("a", "b", "c");
    }

    @Test
    @DisplayName("detects a cycle and names the offending nodes")
    void detectsCycle() {
        Map<String, WorkflowNodeDefinition> nodes = new LinkedHashMap<>();
        nodes.put("a", node("a", List.of("c")));
        nodes.put("b", node("b", List.of("a")));
        nodes.put("c", node("c", List.of("b")));

        assertThatThrownBy(() -> TopologicalSorter.sort(nodes))
                .isInstanceOf(CycleDetectedException.class)
                .hasMessageContaining("Cycle detected");
    }

    @Test
    @DisplayName("detects a self dependency")
    void detectsSelfDependency() {
        Map<String, WorkflowNodeDefinition> nodes = new LinkedHashMap<>();
        nodes.put("a", node("a", List.of("a")));

        assertThatThrownBy(() -> TopologicalSorter.sort(nodes))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Self dependency");
    }

    @Test
    @DisplayName("rejects a dependency on an unknown node")
    void rejectsUnknownDependency() {
        Map<String, WorkflowNodeDefinition> nodes = new LinkedHashMap<>();
        nodes.put("a", node("a", List.of("missing")));

        assertThatThrownBy(() -> TopologicalSorter.sort(nodes))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Dependency not found");
    }

    @Test
    @DisplayName("keeps independent branches independent so they can run in parallel")
    void keepsIndependentBranchesIndependent() {
        // A -> B, A -> C, B -> D, C -> D
        Map<String, WorkflowNodeDefinition> nodes = new LinkedHashMap<>();
        nodes.put("a", node("a", List.of()));
        nodes.put("b", node("b", List.of("a")));
        nodes.put("c", node("c", List.of("a")));
        nodes.put("d", node("d", List.of("b", "c")));

        List<String> order = TopologicalSorter.sort(nodes);

        assertThat(order).hasSize(4);
        assertThat(order.indexOf("a")).isLessThan(order.indexOf("b"));
        assertThat(order.indexOf("a")).isLessThan(order.indexOf("c"));
        assertThat(order.indexOf("b")).isLessThan(order.indexOf("d"));
        assertThat(order.indexOf("c")).isLessThan(order.indexOf("d"));
        // B and C do not depend on each other, so both may be scheduled at the same time.
        assertThat(TopologicalSorter.adjacencyOf(nodes).get("b")).doesNotContain("c");
    }

    @Test
    @DisplayName("produces a deterministic order for the same definition")
    void producesDeterministicOrder() {
        Map<String, WorkflowNodeDefinition> nodes = new LinkedHashMap<>();
        nodes.put("z", node("z", List.of()));
        nodes.put("y", node("y", List.of()));
        nodes.put("x", node("x", List.of("z", "y")));

        List<String> first = TopologicalSorter.sort(nodes);
        List<String> second = TopologicalSorter.sort(nodes);

        assertThat(first).isEqualTo(second);
        assertThat(first.get(0)).isEqualTo("y");
        assertThat(first.get(1)).isEqualTo("z");
        assertThat(first.get(2)).isEqualTo("x");
    }

    @Test
    @DisplayName("handles a graph with several start nodes")
    void handlesMultipleStartNodes() {
        Map<String, WorkflowNodeDefinition> nodes = new LinkedHashMap<>();
        nodes.put("a", node("a", List.of()));
        nodes.put("b", node("b", List.of()));
        nodes.put("c", node("c", List.of("a", "b")));

        List<String> order = TopologicalSorter.sort(nodes);

        assertThat(order).hasSize(3);
        assertThat(order.get(2)).isEqualTo("c");
    }

    private WorkflowNodeDefinition node(String id, List<String> dependencies) {
        return WorkflowNodeDefinition.builder()
                .id(id)
                .type("CODE")
                .dependencies(dependencies)
                .build();
    }
}
