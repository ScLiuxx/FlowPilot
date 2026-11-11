package com.flowpilot.common.util;

import com.flowpilot.workflow.definition.WorkflowDefinitionDocument;
import com.flowpilot.workflow.definition.WorkflowNodeDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests of the JSON helpers, in particular the canonicalisation that the definition hash depends
 * on.
 */
class JsonUtilsTest {

    @Test
    @DisplayName("produces the same hash for two definitions that differ only in key order")
    void canonicalisesKeyOrder() {
        WorkflowDefinitionDocument first = WorkflowDefinitionDocument.builder()
                .name("demo")
                .nodes(List.of(WorkflowNodeDefinition.builder()
                        .id("a")
                        .type("CODE")
                        .config(Map.of("operation", "identity"))
                        .dependencies(List.of())
                        .build()))
                .build();

        // The same document, but the properties are written in a different order.
        String reordered = "{\"nodes\":[{\"dependencies\":[],\"type\":\"CODE\",\"id\":\"a\","
                + "\"config\":{\"operation\":\"identity\"}}],\"name\":\"demo\"}";

        assertThat(JsonUtils.canonicalize(first))
                .isEqualTo(JsonUtils.canonicalize(JsonUtils.fromJson(reordered, WorkflowDefinitionDocument.class)));
        assertThat(JsonUtils.sha256Hex(JsonUtils.canonicalize(first)))
                .isEqualTo(JsonUtils.sha256Hex(JsonUtils.canonicalize(
                        JsonUtils.fromJson(reordered, WorkflowDefinitionDocument.class))));
    }

    @Test
    @DisplayName("keeps the array order, because it is meaningful in a definition")
    void keepsArrayOrder() {
        String first = "{\"nodes\":[{\"id\":\"a\"},{\"id\":\"b\"}]}";
        String second = "{\"nodes\":[{\"id\":\"b\"},{\"id\":\"a\"}]}";

        assertThat(JsonUtils.canonicalize(JsonUtils.fromJson(first, Map.class)))
                .isNotEqualTo(JsonUtils.canonicalize(JsonUtils.fromJson(second, Map.class)));
    }

    @Test
    @DisplayName("produces a stable sha-256 hash")
    void producesStableHash() {
        assertThat(JsonUtils.sha256Hex("flowpilot"))
                .isEqualTo(JsonUtils.sha256Hex("flowpilot"))
                .hasSize(64);
        assertThat(JsonUtils.sha256Hex("flowpilot"))
                .isNotEqualTo(JsonUtils.sha256Hex("flowpilo"));
    }

    @Test
    @DisplayName("round trips a map")
    void roundTripsMap() {
        Map<String, Object> source = Map.of("key", "value", "number", 42);

        Map<String, Object> parsed = JsonUtils.toMap(JsonUtils.toJson(source));

        assertThat(parsed).containsEntry("key", "value").containsEntry("number", 42);
    }

    @Test
    @DisplayName("returns an empty map for a blank json")
    void returnsEmptyMapForBlankJson() {
        assertThat(JsonUtils.toMap((String) null)).isEmpty();
        assertThat(JsonUtils.toMap("")).isEmpty();
    }
}
