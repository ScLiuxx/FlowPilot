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
    }
}
