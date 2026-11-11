package com.flowpilot.execution.executor;

import com.flowpilot.execution.model.NodeType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Merges the results of parallel branches.
 *
 * <p>In {@code ALL} mode the outputs of every branch are combined into one result. In
 * {@code ANY} mode the first branch that produced output wins, which is what a conditional
 * workflow needs when only one branch is taken.
 */
@Slf4j
@Component
public class MergeNodeExecutor implements NodeExecutor {

    @Override
    public NodeType supportType() {
        return NodeType.MERGE;
    }

    @Override
    public NodeResult execute(NodeContext context) {
        Map<String, Object> config = context.getConfig();
        String mode = String.valueOf(config.getOrDefault("mode", "ALL")).toUpperCase();
        Map<String, Object> upstream = context.getUpstreamOutputs();

        Map<String, Object> merged = new LinkedHashMap<>();
        Map<String, Object> branches = new LinkedHashMap<>();

        if ("ANY".equals(mode)) {
            for (Map.Entry<String, Object> entry : upstream.entrySet()) {
                Map<String, Object> branchOutput = asMap(entry.getValue());
                branches.put(entry.getKey(), branchOutput);
                if (merged.isEmpty() && !branchOutput.isEmpty()) {
                    merged.putAll(branchOutput);
                }
            }
        } else {
            // The workflow input is the base, the branch outputs are layered on top.
            merged.putAll(asMap(context.getInput()));
            upstream.forEach((key, value) -> {
                Map<String, Object> branchOutput = asMap(value);
                branches.put(key, branchOutput);
                merged.putAll(branchOutput);
            });
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("mode", mode);
        output.put("merged", merged);
        output.put("branches", branches);
        return NodeResult.success(output);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }
}
