package com.flowpilot.execution.executor;

import com.flowpilot.execution.model.NodeType;
import com.flowpilot.messaging.protocol.ErrorCategory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Selects a branch based on a condition.
 *
 * <p>The condition is evaluated against the node input. The selected branch is published in the
 * output so the scheduler can skip the nodes of the other branches.
 */
@Slf4j
@Component
public class ConditionNodeExecutor implements NodeExecutor {

    @Override
    public NodeType supportType() {
        return NodeType.CONDITION;
    }

    @Override
    public NodeResult execute(NodeContext context) {
        Map<String, Object> config = context.getConfig();
        Object expression = config.get("expression");
        if (expression == null) {
            return NodeResult.failure("CONDITION_MISSING_EXPRESSION", ErrorCategory.VALIDATION,
                    "The CONDITION node requires config.expression", false);
        }
        try {
            boolean matched = evaluate(String.valueOf(expression), context.getInput());
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("matched", matched);
            output.put("expression", expression);
            output.put("branch", matched
                    ? String.valueOf(config.getOrDefault("thenBranch", "true"))
                    : String.valueOf(config.getOrDefault("elseBranch", "false")));
            return NodeResult.success(output);
        } catch (EvaluationException ex) {
            return NodeResult.failure("CONDITION_EVALUATION_ERROR", ErrorCategory.VALIDATION,
                    ex.getMessage(), false);
        }
    }

    /**
     * Evaluates a simple expression of the form {@code field op value}.
     *
     * <p>Only comparison operators are supported. A full expression language would be a security
     * risk, because the expression comes from a user supplied workflow definition.
     */
    private boolean evaluate(String expression, Map<String, Object> input) {
        String[] parts = expression.trim().split("\\s+");
        if (parts.length != 3) {
            throw new EvaluationException("The condition expression must be 'field operator value'");
        }
        String field = parts[0];
        String operator = parts[1];
        String expected = parts[2];

        Object actual = resolve(input, field);
        String actualText = actual == null ? "" : String.valueOf(actual);
        String expectedText = unquote(expected);

        return switch (operator) {
            case "==" -> actualText.equals(expectedText);
            case "!=" -> !actualText.equals(expectedText);
            case ">" -> compare(actual, expectedText) > 0;
            case ">=" -> compare(actual, expectedText) >= 0;
            case "<" -> compare(actual, expectedText) < 0;
            case "<=" -> compare(actual, expectedText) <= 0;
            case "contains" -> actualText.contains(expectedText);
            case "startsWith" -> actualText.startsWith(expectedText);
            case "endsWith" -> actualText.endsWith(expectedText);
            case "exists" -> actual != null;
            default -> throw new EvaluationException("Unsupported operator: " + operator);
        };
    }

    private Object resolve(Map<String, Object> input, String field) {
        if (field.contains(".")) {
            String[] parts = field.split("\\.");
            Map<String, Object> current = input;
            for (int i = 0; i < parts.length - 1; i++) {
                Object next = current.get(parts[i]);
                if (!(next instanceof Map<?, ?> map)) {
                    return null;
                }
                current = (Map<String, Object>) map;
            }
            return current.get(parts[parts.length - 1]);
        }
        return input.get(field);
    }

    private int compare(Object actual, String expected) {
        if (actual instanceof Number number) {
            try {
                return Double.compare(number.doubleValue(), Double.parseDouble(expected));
            } catch (NumberFormatException ex) {
                throw new EvaluationException("The right hand side of the comparison is not a number");
            }
        }
        try {
            return Double.compare(Double.parseDouble(String.valueOf(actual)), Double.parseDouble(expected));
        } catch (NumberFormatException ex) {
            throw new EvaluationException("The compared values are not numbers");
        }
    }

    private String unquote(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    static class EvaluationException extends RuntimeException {
        EvaluationException(String message) {
            super(message);
        }
    }
}
