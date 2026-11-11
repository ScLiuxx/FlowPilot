package com.flowpilot.common.metrics;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The names of the metrics have to match the dashboard, because a dashboard that queries a
 * metric that does not exist shows an empty panel.
 */
class MetricNameTest {

    private PrometheusMeterRegistry registry() {
        return new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    }

    @Test
    @DisplayName("the workflow duration is a histogram with the expected name")
    void workflowDuration() {
        PrometheusMeterRegistry registry = registry();
        FlowPilotMetrics metrics = new FlowPilotMetrics(registry);
        metrics.recordWorkflowDuration("SUCCEEDED", 1200);

        String scrape = registry.scrape();
        assertThat(scrape).contains("flowpilot_workflow_duration_seconds");
    }

    @Test
    @DisplayName("the node duration is a histogram with the expected name")
    void nodeDuration() {
        PrometheusMeterRegistry registry = registry();
        FlowPilotMetrics metrics = new FlowPilotMetrics(registry);
        metrics.recordNodeDuration("HTTP", "SUCCEEDED", 300);

        assertThat(registry.scrape()).contains("flowpilot_node_duration_seconds");
    }

    @Test
    @DisplayName("the llm call counter is a counter with the expected name")
    void llmCalls() {
        PrometheusMeterRegistry registry = registry();
        FlowPilotMetrics metrics = new FlowPilotMetrics(registry);
        metrics.countLlmCalls(4);

        assertThat(registry.scrape()).contains("flowpilot_llm_calls_total");
    }

    @Test
    @DisplayName("the tool call counter carries the outcome")
    void toolCalls() {
        PrometheusMeterRegistry registry = registry();
        FlowPilotMetrics metrics = new FlowPilotMetrics(registry);
        metrics.countToolCalls(4, 1);

        String scrape = registry.scrape();
        assertThat(scrape).contains("flowpilot_tool_calls_total");
        assertThat(scrape).contains("outcome=\"success\"");
        assertThat(scrape).contains("outcome=\"failure\"");
    }

    @Test
    @DisplayName("the durations are published as a histogram")
    void durationsAreHistograms() {
        PrometheusMeterRegistry registry = registry();
        FlowPilotMetrics metrics = new FlowPilotMetrics(registry);
        metrics.recordWorkflowDuration("SUCCEEDED", 1200);

        String scrape = registry.scrape();
        assertThat(scrape).contains("flowpilot_workflow_duration_seconds_bucket");
        assertThat(scrape).contains("flowpilot_workflow_duration_seconds_sum");
        assertThat(scrape).contains("flowpilot_workflow_duration_seconds_count");
    }

    @Test
    @DisplayName("the agent duration and the token counter are reported")
    void agentDurationAndTokens() {
        PrometheusMeterRegistry registry = registry();
        FlowPilotMetrics metrics = new FlowPilotMetrics(registry);
        metrics.recordAgentDuration("REPOSITORY_ANALYST", "SUCCEEDED", 5000);
        metrics.countTokens(100, 50);

        String scrape = registry.scrape();
        assertThat(scrape).contains("flowpilot_agent_duration_seconds");
        assertThat(scrape).contains("flowpilot_tokens_input_total");
        assertThat(scrape).contains("flowpilot_tokens_output_total");
    }

    @Test
    @DisplayName("the retry counter is reported per node type")
    void retries() {
        PrometheusMeterRegistry registry = registry();
        FlowPilotMetrics metrics = new FlowPilotMetrics(registry);
        metrics.countNodeRetry("HTTP");

        assertThat(registry.scrape()).contains("flowpilot_node_retries_total");
    }
}
