package com.flowpilot.workflow.parser;

import com.flowpilot.common.result.ErrorCode;
import com.flowpilot.common.exception.BizException;
import com.flowpilot.workflow.definition.WorkflowDefinitionDocument;
import com.flowpilot.workflow.definition.WorkflowNodeDefinition;
import com.flowpilot.execution.model.NodeType;
import lombok.Getter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates a workflow definition before it can be published.
 *
 * <p>The validator never trusts user supplied JSON. It checks node id uniqueness, node types,
 * dependency existence, self dependencies, cycles, unreachable nodes and type specific
 * configuration.
 */
public final class WorkflowValidator {

    private WorkflowValidator() {
    }

    @Getter
    public static class ValidationResult {
        private final boolean valid;
        private final List<String> topologicalOrder;
        private final List<String> errors;
        private final List<String> warnings;

        public ValidationResult(boolean valid, List<String> topologicalOrder,
                                List<String> errors, List<String> warnings) {
            this.valid = valid;
            this.topologicalOrder = topologicalOrder == null ? List.of() : List.copyOf(topologicalOrder);
            this.errors = errors == null ? List.of() : List.copyOf(errors);
            this.warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    /**
     * Validates the document and returns the result instead of throwing, so the console can show
     * every problem at once.
     */
    public static ValidationResult validate(WorkflowDefinitionDocument document) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (document == null) {
            errors.add("Workflow definition must not be null");
            return new ValidationResult(false, List.of(), errors, warnings);
        }

        List<WorkflowNodeDefinition> nodes = document.nodes();
        if (nodes.isEmpty()) {
            errors.add("Workflow must contain at least one node");
            return new ValidationResult(false, List.of(), errors, warnings);
        }

        Map<String, WorkflowNodeDefinition> nodeMap = new LinkedHashMap<>();
        for (WorkflowNodeDefinition node : nodes) {
            if (node.getId() == null || node.getId().isBlank()) {
                errors.add("Every node must have an id");
                continue;
            }
            if (nodeMap.containsKey(node.getId())) {
                errors.add("Duplicate node id: " + node.getId());
                continue;
            }
            nodeMap.put(node.getId(), node);
        }

        for (WorkflowNodeDefinition node : nodeMap.values()) {
            validateNodeType(node, errors);
            validateDependencies(node, nodeMap, errors);
            validateConfig(node, errors, warnings);
        }

        List<String> topologicalOrder = List.of();
        if (errors.isEmpty()) {
            try {
                topologicalOrder = TopologicalSorter.sort(nodeMap);
            } catch (CycleDetectedException ex) {
                errors.add(ex.getMessage());
            } catch (IllegalArgumentException ex) {
                errors.add(ex.getMessage());
            }
        }

        // The structure checks only make sense once the graph itself is valid.
        if (errors.isEmpty()) {
            validateIsolatedNodes(nodeMap, errors);
            validateBranches(nodeMap, errors);
            validateInputOutputMapping(nodeMap, errors);
        }

        if (!errors.isEmpty()) {
            return new ValidationResult(false, topologicalOrder, errors, warnings);
        }

        return new ValidationResult(true, topologicalOrder, errors, warnings);
    }

    /**
     * Validates and throws a {@link BizException} when the definition is not usable.
     */
    public static ValidationResult validateOrThrow(WorkflowDefinitionDocument document) {
        ValidationResult result = validate(document);
        if (!result.isValid()) {
            throw new BizException(ErrorCode.WF_VALIDATION_ERROR, String.join("; ", result.getErrors()));
        }
        return result;
    }

    private static void validateNodeType(WorkflowNodeDefinition node, List<String> errors) {
        if (node.getType() == null || node.getType().isBlank()) {
            errors.add("Node " + node.getId() + " must declare a type");
            return;
        }
        try {
            NodeType.from(node.getType());
        } catch (IllegalArgumentException ex) {
            errors.add("Node " + node.getId() + " has an unsupported type: " + node.getType());
        }
    }

    private static void validateDependencies(WorkflowNodeDefinition node,
                                             Map<String, WorkflowNodeDefinition> nodeMap,
                                             List<String> errors) {
        Set<String> seen = new HashSet<>();
        for (String dependency : node.dependencies()) {
            if (dependency == null || dependency.isBlank()) {
                errors.add("Node " + node.getId() + " has an empty dependency");
                continue;
            }
            if (dependency.equals(node.getId())) {
                errors.add("Node " + node.getId() + " depends on itself");
                continue;
            }
            if (!nodeMap.containsKey(dependency)) {
                errors.add("Node " + node.getId() + " depends on an unknown node: " + dependency);
                continue;
            }
            if (!seen.add(dependency)) {
                errors.add("Node " + node.getId() + " declares the dependency " + dependency + " twice");
            }
        }
    }

    private static void validateConfig(WorkflowNodeDefinition node,
                                        List<String> errors,
                                        List<String> warnings) {
        NodeType type;
        try {
            type = NodeType.from(node.getType());
        } catch (IllegalArgumentException ex) {
            return;
        }
        Map<String, Object> config = node.config();

        switch (type) {
            case HTTP -> {
                if (config.get("url") == null && config.get("operation") == null) {
                    errors.add("HTTP node " + node.getId() + " requires either a url or an operation");
                }
            }
            case AGENT -> {
                Object agentType = config.get("agentType");
                if (agentType == null || agentType.toString().isBlank()) {
                    errors.add("AGENT node " + node.getId() + " requires config.agentType");
                }
            }
            case CONDITION -> {
                if (config.get("expression") == null && config.get("branches") == null) {
                    errors.add("CONDITION node " + node.getId()
                            + " requires config.expression or config.branches");
                }
            }
            case MERGE -> {
                Object mode = config.get("mode");
                if (mode != null && !List.of("ALL", "ANY").contains(mode.toString().toUpperCase())) {
                    errors.add("MERGE node " + node.getId() + " config.mode must be ALL or ANY");
                }
            }
            case CODE -> {
                if (config.get("expression") == null && config.get("script") == null
                        && config.get("operation") == null) {
                    warnings.add("CODE node " + node.getId()
                            + " has no expression, script or operation configured");
                }
            }
            case HUMAN -> {
                Object approvalType = config.get("approvalType");
                if (approvalType == null || approvalType.toString().isBlank()) {
                    errors.add("HUMAN node " + node.getId() + " requires config.approvalType");
                }
            }
            default -> {
                // LLM and MERGE need no mandatory configuration.
            }
        }

        // The schema of the type catches the mistakes the switch above cannot, for example a
        // key that the type does not understand.
        for (String problem : NodeSchemaRegistry.validateConfig(type, config)) {
            errors.add("Node " + node.getId() + " has an invalid configuration: " + problem);
        }
    }

    /**
     * Checks that no node is isolated.
     *
     * <p>A node is isolated when it has no dependency and no successor. Such a node runs on its
     * own and its result never reaches anything, which is almost always a mistake in the
     * definition.
     */
    private static void validateIsolatedNodes(Map<String, WorkflowNodeDefinition> nodeMap,
                                              List<String> errors) {
        if (nodeMap.size() < 2) {
            // A workflow with a single node is not isolated, it is the whole workflow.
            return;
        }
        Set<String> referenced = new HashSet<>();
        for (WorkflowNodeDefinition node : nodeMap.values()) {
            referenced.addAll(node.dependencies());
        }
        for (WorkflowNodeDefinition node : nodeMap.values()) {
            if (node.dependencies().isEmpty() && !referenced.contains(node.getId())) {
                errors.add("Node " + node.getId() + " is isolated, it neither depends on a node "
                        + "nor is a dependency of another node");
            }
        }
    }

    /**
     * Checks that the branches of a condition point at real nodes.
     *
     * <p>A branch that names an unknown node never runs, so the workflow would wait for a node
     * that does not exist.
     */
    private static void validateBranches(Map<String, WorkflowNodeDefinition> nodeMap,
                                         List<String> errors) {
        for (WorkflowNodeDefinition node : nodeMap.values()) {
            if (!NodeType.CONDITION.name().equalsIgnoreCase(node.getType())) {
                continue;
            }
            Object branches = node.config().get("branches");
            if (!(branches instanceof Map<?, ?> map)) {
                continue;
            }
            map.forEach((branch, targets) -> {
                if (!(targets instanceof Iterable<?> iterable)) {
                    errors.add("The branch " + branch + " of the condition node " + node.getId()
                            + " must be a list of node keys");
                    return;
                }
                iterable.forEach(target -> {
                    String key = String.valueOf(target);
                    if (!nodeMap.containsKey(key)) {
                        errors.add("The branch " + branch + " of the condition node " + node.getId()
                                + " names an unknown node: " + key);
                    }
                });
            });
        }
    }

    /**
     * Checks that the output of a node still fits the input of its successor.
     *
     * <p>The check only applies to the nodes that declare a schema. A node without a schema
     * passes anything, so the check is skipped for it.
     */
    private static void validateInputOutputMapping(Map<String, WorkflowNodeDefinition> nodeMap,
                                                   List<String> errors) {
        for (WorkflowNodeDefinition consumer : nodeMap.values()) {
            var consumerInput = NodeSchemaRegistry.inputSchema(consumer.config());
            if (consumerInput == null) {
                continue;
            }
            for (String dependency : consumer.dependencies()) {
                WorkflowNodeDefinition producer = nodeMap.get(dependency);
                if (producer == null) {
                    continue;
                }
                var producerOutput = NodeSchemaRegistry.outputSchema(producer.config());
                errors.addAll(NodeSchemaRegistry.checkMapping(producer.getId(), producerOutput,
                        consumer.getId(), consumerInput));
            }
        }
    }
}
