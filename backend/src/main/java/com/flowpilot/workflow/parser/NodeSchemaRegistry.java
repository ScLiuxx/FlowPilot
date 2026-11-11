package com.flowpilot.workflow.parser;

import com.flowpilot.execution.model.NodeType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The schemas of the node configuration and the helpers to check the data of a node.
 *
 * <p>The architecture design asks for a schema validation of the node configuration and for a
 * check of the input and output mapping. The configuration schema of a node type describes the
 * keys the type understands, so a typo in a configuration is reported when the workflow is
 * published instead of when it runs.
 *
 * <p>The input and output schemas are optional. A node that declares them describes the shape of
 * the data it consumes and produces, which lets the validator check that the output of a node
 * still fits the input of its successor.
 */
public final class NodeSchemaRegistry {

    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);

    /** The node factory that turns a java value into a JSON node. */
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * The configuration schema of every node type.
     *
     * <p>The schemas are deliberately permissive: they forbid the keys that would certainly be a
     * mistake and require the keys the type cannot work without.
     */
    private static final Map<NodeType, String> CONFIG_SCHEMAS = buildConfigSchemas();

    private NodeSchemaRegistry() {
    }

    /**
     * Validates the configuration of a node against the schema of its type.
     *
     * @return the problems of the configuration, empty when the configuration is valid
     */
    public static List<String> validateConfig(NodeType type, Map<String, Object> config) {
        String schema = CONFIG_SCHEMAS.get(type);
        if (schema == null) {
            return List.of();
        }
        JsonNode instance = MAPPER.valueToTree(config == null ? Map.of() : config);
        Set<ValidationMessage> messages = FACTORY.getSchema(schema).validate(instance);
        List<String> problems = new ArrayList<>();
        for (ValidationMessage message : messages) {
            problems.add(message.getMessage());
        }
        return problems;
    }

    /**
     * The schema of the data a node consumes, empty when the node does not declare one.
     */
    public static JsonNode inputSchema(Map<String, Object> config) {
        return schemaOf(config, "inputSchema");
    }

    /**
     * The schema of the data a node produces, empty when the node does not declare one.
     */
    public static JsonNode outputSchema(Map<String, Object> config) {
        return schemaOf(config, "outputSchema");
    }

    /**
     * Validates a value against a schema.
     *
     * @return the problems of the value, empty when the value fits the schema
     */
    public static List<String> validate(JsonNode schema, java.util.Map<String, Object> value) {
        if (schema == null || schema.isEmpty()) {
            return List.of();
        }
        JsonNode instance = MAPPER.valueToTree(value == null ? Map.of() : value);
        JsonSchema compiled = FACTORY.getSchema(schema.toString());
        Set<ValidationMessage> messages = compiled.validate(instance);
        List<String> problems = new ArrayList<>();
        for (ValidationMessage message : messages) {
            problems.add(message.getMessage());
        }
        return problems;
    }

    private static JsonNode schemaOf(Map<String, Object> config, String key) {
        if (config == null) {
            return null;
        }
        Object value = config.get(key);
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
            return null;
        }
        return MAPPER.valueToTree(map);
    }

    /**
     * Checks that the output of a producer still fits the input of a consumer.
     *
     * <p>The check is only possible when both nodes declare a schema. It answers two questions:
     * does the producer deliver every property the consumer requires, and do the types of the
     * shared properties agree.
     *
     * @return the problems of the mapping, empty when the mapping is valid
     */
    public static List<String> checkMapping(String producerKey,
                                            JsonNode producerOutput,
                                            String consumerKey,
                                            JsonNode consumerInput) {
        List<String> problems = new ArrayList<>();
        if (producerOutput == null || consumerInput == null) {
            return problems;
        }
        JsonNode produced = producerOutput.path("properties");
        JsonNode required = consumerInput.path("required");
        if (required.isArray()) {
            for (JsonNode property : required) {
                String name = property.asText();
                if (!produced.has(name)) {
                    problems.add("Node " + consumerKey + " requires the property '" + name
                            + "', but node " + producerKey + " does not produce it");
                }
            }
        }
        JsonNode consumed = consumerInput.path("properties");
        var fields = produced.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            JsonNode consumerProperty = consumed.get(field.getKey());
            if (consumerProperty == null) {
                continue;
            }
            JsonNode producedType = field.getValue().path("type");
            JsonNode consumedType = consumerProperty.path("type");
            if (!producedType.isMissingNode() && !consumedType.isMissingNode()
                    && !producedType.equals(consumedType)) {
                problems.add("The property '" + field.getKey() + "' has the type "
                        + producedType.asText() + " in node " + producerKey
                        + " but the type " + consumedType.asText() + " in node " + consumerKey);
            }
        }
        return problems;
    }

    private static Map<NodeType, String> buildConfigSchemas() {
        Map<NodeType, String> schemas = new LinkedHashMap<>();

        schemas.put(NodeType.HTTP, """
                {
                  "type": "object",
                  "anyOf": [
                    { "required": ["url"] },
                    { "required": ["operation"] }
                  ],
                  "properties": {
                    "url": { "type": "string" },
                    "operation": { "type": "string" },
                    "method": { "type": "string", "enum": ["GET", "POST", "PUT", "DELETE", "PATCH"] },
                    "headers": { "type": "object" },
                    "body": {},
                    "timeoutSeconds": { "type": "integer", "minimum": 1 }
                  },
                  "additionalProperties": true
                }
                """);

        schemas.put(NodeType.CODE, """
                {
                  "type": "object",
                  "properties": {
                    "operation": { "type": "string" },
                    "expression": { "type": "string" },
                    "script": { "type": "string" },
                    "inputSchema": { "type": "object" },
                    "outputSchema": { "type": "object" }
                  },
                  "additionalProperties": true
                }
                """);

        schemas.put(NodeType.CONDITION, """
                {
                  "type": "object",
                  "anyOf": [
                    { "required": ["expression"] },
                    { "required": ["branches"] }
                  ],
                  "properties": {
                    "expression": { "type": "string" },
                    "branches": {
                      "type": "object",
                      "additionalProperties": {
                        "type": "array",
                        "items": { "type": "string" }
                      }
                    },
                    "inputSchema": { "type": "object" },
                    "outputSchema": { "type": "object" }
                  },
                  "additionalProperties": true
                }
                """);

        schemas.put(NodeType.MERGE, """
                {
                  "type": "object",
                  "properties": {
                    "mode": { "type": "string", "enum": ["ALL", "ANY"] },
                    "strategy": { "type": "string" },
                    "inputSchema": { "type": "object" },
                    "outputSchema": { "type": "object" }
                  },
                  "additionalProperties": true
                }
                """);

        schemas.put(NodeType.HUMAN, """
                {
                  "type": "object",
                  "required": ["approvalType"],
                  "properties": {
                    "approvalType": { "type": "string" },
                    "title": { "type": "string" },
                    "description": { "type": "string" },
                    "timeoutMinutes": { "type": "integer", "minimum": 0 },
                    "timeoutAction": { "type": "string", "enum": ["FAIL", "AUTO_APPROVE"] },
                    "outputSchema": { "type": "object" }
                  },
                  "additionalProperties": true
                }
                """);

        schemas.put(NodeType.AGENT, """
                {
                  "type": "object",
                  "required": ["agentType"],
                  "properties": {
                    "agentType": { "type": "string" },
                    "model": { "type": "string" },
                    "maxIterations": { "type": "integer", "minimum": 1 },
                    "maxToolCalls": { "type": "integer", "minimum": 0 },
                    "maxTokens": { "type": "integer", "minimum": 0 },
                    "timeoutSeconds": { "type": "integer", "minimum": 1 },
                    "inputSchema": { "type": "object" },
                    "outputSchema": { "type": "object" }
                  },
                  "additionalProperties": true
                }
                """);

        schemas.put(NodeType.LLM, """
                {
                  "type": "object",
                  "properties": {
                    "agentType": { "type": "string" },
                    "model": { "type": "string" },
                    "prompt": { "type": "string" },
                    "systemPrompt": { "type": "string" },
                    "temperature": { "type": "number" },
                    "maxTokens": { "type": "integer", "minimum": 0 },
                    "timeoutSeconds": { "type": "integer", "minimum": 1 },
                    "inputSchema": { "type": "object" },
                    "outputSchema": { "type": "object" }
                  },
                  "additionalProperties": true
                }
                """);

        return Map.copyOf(schemas);
    }
}
