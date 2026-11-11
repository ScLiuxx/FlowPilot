package com.flowpilot.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * The metrics of the platform.
 *
 * <p>The architecture design lists the numbers the platform has to report: the duration of a
 * workflow, the duration of a node, the duration of an agent, the number of model calls, the
 * token consumption, the success rate of the tool calls, the number of node retries and the
 * failure rate of the workflows.
 *
 * <p>The metric names follow the Prometheus convention, so the dashboard can use them directly.
 */
@Component
@RequiredArgsConstructor
public class FlowPilotMetrics {

    private final MeterRegistry registry;

    /**
     * The duration of a workflow execution.
     */
    public void recordWorkflowDuration(String status, long millis) {
        Timer.builder("flowpilot_workflow_duration_seconds")
                .description("The duration of a workflow execution")
                .tag("status", status)
                .publishPercentileHistogram()
                .register(registry)
                .record(millis, TimeUnit.MILLISECONDS);
    }

    /**
     * The duration of a node execution.
     */
    public void recordNodeDuration(String nodeType, String status, long millis) {
        Timer.builder("flowpilot_node_duration_seconds")
                .description("The duration of a node execution")
                .tag("node_type", nodeType)
                .tag("status", status)
                .publishPercentileHistogram()
                .register(registry)
                .record(millis, TimeUnit.MILLISECONDS);
    }

    /**
     * The duration of an agent invocation.
     */
    public void recordAgentDuration(String agentType, String status, long millis) {
        Timer.builder("flowpilot_agent_duration_seconds")
                .description("The duration of an agent invocation")
                .tag("agent_type", agentType)
                .tag("status", status)
                .publishPercentileHistogram()
                .register(registry)
                .record(millis, TimeUnit.MILLISECONDS);
    }

    /**
     * The number of model calls.
     */
    public void countLlmCalls(long count) {
        if (count <= 0) {
            return;
        }
        Counter.builder("flowpilot_llm_calls_total")
                .description("The number of model calls")
                .register(registry)
                .increment(count);
    }

    /**
     * The number of tool calls.
     *
     * <p>The calls are counted separately for the successful and the failed ones, so the success
     * rate is a ratio of two counters and not a calculation over a time series.
     */
    public void countToolCalls(long count, long failures) {
        long successful = Math.max(0, count - failures);
        if (successful > 0) {
            Counter.builder("flowpilot_tool_calls_total")
                    .description("The number of tool calls")
                    .tag("outcome", "success")
                    .register(registry)
                    .increment(successful);
        }
        if (failures > 0) {
            Counter.builder("flowpilot_tool_calls_total")
                    .description("The number of tool calls")
                    .tag("outcome", "failure")
                    .register(registry)
                    .increment(failures);
            Counter.builder("flowpilot_tool_failures_total")
                    .description("The number of failed tool calls")
                    .register(registry)
                    .increment(failures);
        }
    }

    /**
     * The number of consumed tokens.
     */
    public void countTokens(long inputTokens, long outputTokens) {
        if (inputTokens > 0) {
            Counter.builder("flowpilot_tokens_input_total")
                    .description("The number of consumed input tokens")
                    .register(registry)
                    .increment(inputTokens);
        }
        if (outputTokens > 0) {
            Counter.builder("flowpilot_tokens_output_total")
                    .description("The number of produced output tokens")
                    .register(registry)
                    .increment(outputTokens);
        }
    }

    /**
     * The number of retried nodes.
     */
    public void countNodeRetry(String nodeType) {
        Counter.builder("flowpilot_node_retries_total")
                .description("The number of retried node executions")
                .tag("node_type", nodeType)
                .register(registry)
                .increment();
    }

    /**
     * The number of failed workflows.
     */
    public void countWorkflowFailure() {
        Counter.builder("flowpilot_workflow_failures_total")
                .description("The number of failed workflow executions")
                .register(registry)
                .increment();
    }

    /**
     * The number of executed nodes.
     */
    public void countNodeExecution(String nodeType, String status) {
        Counter.builder("flowpilot_node_executions_total")
                .description("The number of executed nodes")
                .tag("node_type", nodeType)
                .tag("status", status)
                .register(registry)
                .increment();
    }
}
