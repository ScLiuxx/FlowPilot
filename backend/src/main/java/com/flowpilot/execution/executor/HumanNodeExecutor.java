package com.flowpilot.execution.executor;

import com.flowpilot.execution.model.NodeType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
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
}
