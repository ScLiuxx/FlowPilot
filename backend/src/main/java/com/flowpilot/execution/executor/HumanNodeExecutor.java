package com.flowpilot.execution.executor;

import com.flowpilot.execution.model.NodeType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A node that pauses the workflow until a human decides.
 *
 * <p>The Java worker does not block on it. It returns a result that puts the node into the human
 * waiting state; the approval module then resumes the workflow once a decision arrives.
 */
@Slf4j
@Component
public class HumanNodeExecutor implements NodeExecutor {

    /**
     * The fields of an upstream result a reviewer needs in order to decide.
     *
     * <p>The approval request is what the reviewer sees. Without the content of the decision the
     * approval asks for a yes or a no without showing what is being approved.
     */
    private static final List<String> REVIEW_FIELDS = List.of(
            "riskLevel",
            "architectureAnalysis",
            "maintenanceAnalysis",
            "riskAnalysis",
            "recommendation",
            "conclusion");

    @Override
    public NodeType supportType() {
        return NodeType.HUMAN;
    }

    @Override
    public NodeResult execute(NodeContext context) {
        Map<String, Object> config = context.getConfig();
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("approvalType", String.valueOf(config.getOrDefault("approvalType", "GENERIC")));
        output.put("title", String.valueOf(config.getOrDefault("title", "请确认执行结果")));
        output.put("description", String.valueOf(config.getOrDefault("description", "")));
        output.put("awaitingDecision", true);
        // The content of the decision travels with the request, so the reviewer can read what
        // they are asked to approve.
        Map<String, Object> review = reviewContent(context.getUpstreamOutputs());
        if (!review.isEmpty()) {
            output.put("analysis", review);
        }
        // The deadline of the approval travels with the request, so the approval service can
        // close the approval when nobody answers it.
        Object timeoutMinutes = config.get("timeoutMinutes");
        if (timeoutMinutes != null) {
            output.put("timeoutMinutes", timeoutMinutes);
        }
        Object timeoutAction = config.get("timeoutAction");
        if (timeoutAction != null) {
            output.put("timeoutAction", timeoutAction);
        }
        return NodeResult.success(output);
    }

    /**
     * The analysis of the predecessors the reviewer is asked about.
     *
     * <p>The fields are taken from every predecessor that carries them, so the approval shows
     * the analysis of the node that ran before it.
     */
    private Map<String, Object> reviewContent(Map<String, Object> upstreamOutputs) {
        Map<String, Object> review = new LinkedHashMap<>();
        if (upstreamOutputs == null) {
            return review;
        }
        for (Object upstream : upstreamOutputs.values()) {
            if (!(upstream instanceof Map<?, ?> values)) {
                continue;
            }
            for (String field : REVIEW_FIELDS) {
                Object value = values.get(field);
                if (value != null && !String.valueOf(value).isBlank()) {
                    review.putIfAbsent(field, value);
                }
            }
        }
        return review;
    }
}
