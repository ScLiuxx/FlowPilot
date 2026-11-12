package com.flowpilot.execution.executor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests of the issue statistics.
 *
 * <p>The GitHub issues endpoint only answers with the <em>open</em> issues by
 * default. A single collection therefore never contains the closed issues, and
 * the number of items is the number of the collected page — not the number of
 * issues the repository ever had. The statistics must therefore never present a
 * sample as the total.
 */
class IssueMetricsTest {

    private final CodeNodeExecutor executor = new CodeNodeExecutor();

    /**
     * Builds the summary of a search response: the exact number of matching
     * items and the page that was returned.
     */
    private Map<String, Object> exactSummary(long total, List<Map<String, Object>> items) {
        Map<String, Object> collection = new LinkedHashMap<>();
        collection.put("source", "search");
        collection.put("collectedAt", "2026-11-12T10:00:00Z");
        collection.put("countExact", true);
        collection.put("itemsSampled", items.size() < total);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", total);
        body.put("items", items);
        body.put("truncated", items.size() < total);
        body.put("collection", collection);
        return body;
    }

    /**
     * Builds the summary of a paginated list response.
     */
    private Map<String, Object> listSummary(int collected, boolean complete, List<Map<String, Object>> items) {
        Map<String, Object> collection = new LinkedHashMap<>();
        collection.put("source", "list");
        collection.put("collectedAt", "2026-11-12T10:00:00Z");
        collection.put("pagesFetched", 1);
        collection.put("complete", complete);
        collection.put("countExact", false);
        collection.put("itemsSampled", true);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", collected);
        body.put("items", items);
        body.put("truncated", false);
        body.put("collection", collection);
        return body;
    }

    private Map<String, Object> nodeOutput(Map<String, Object> body) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("statusCode", 200);
        output.put("body", body);
        return output;
    }

    private Map<String, Object> issue(String number, String state, boolean pullRequest) {
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("number", number);
        issue.put("title", "issue " + number);
        issue.put("state", state);
        if (pullRequest) {
            issue.put("pull_request", Map.of("url", "https://api.github.com/pull/" + number));
        }
        return issue;
    }

    @Test
    @DisplayName("counts the open and the closed issues separately")
    void countsOpenAndClosedSeparately() {
        Map<String, Object> upstream = new LinkedHashMap<>();
        upstream.put("collect_open_issues", nodeOutput(exactSummary(42,
                List.of(issue("1", "open", false), issue("2", "open", false)))));
        upstream.put("collect_closed_issues", nodeOutput(exactSummary(137,
                List.of(issue("3", "closed", false)))));
        upstream.put("collect_pull_requests", nodeOutput(exactSummary(9,
                List.of(issue("4", "open", true)))));

        NodeContext context = NodeContext.builder()
                .nodeKey("compute_metrics")
                .config(Map.of("operation", "issue_metrics"))
                .upstreamOutputs(upstream)
                .build();

        Map<String, Object> metrics = invokeIssueMetrics(context);

        assertThat(metrics.get("openIssues")).isEqualTo(42L);
        assertThat(metrics.get("closedIssues")).isEqualTo(137L);
        assertThat(metrics.get("totalIssues")).isEqualTo(179L);
        assertThat(metrics.get("pullRequests")).isEqualTo(9L);
        assertThat(metrics.get("countsExact")).isEqualTo(true);
    }

    @Test
    @DisplayName("never presents a sampled count as the exact total")
    void marksSampledCounts() {
        Map<String, Object> upstream = new LinkedHashMap<>();
        upstream.put("collect_open_issues", nodeOutput(listSummary(30, false,
                List.of(issue("1", "open", false)))));
        upstream.put("collect_closed_issues", nodeOutput(listSummary(10, false,
                List.of(issue("3", "closed", false)))));
        upstream.put("collect_pull_requests", nodeOutput(listSummary(5, true,
                List.of(issue("4", "open", true)))));

        NodeContext context = NodeContext.builder()
                .nodeKey("compute_metrics")
                .config(Map.of("operation", "issue_metrics"))
                .upstreamOutputs(upstream)
                .build();

        Map<String, Object> metrics = invokeIssueMetrics(context);

        // The numbers are the collected ones — and they are marked as such.
        assertThat(metrics.get("openIssues")).isEqualTo(30L);
        assertThat(metrics.get("countsExact")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        Map<String, Object> collection = (Map<String, Object>) metrics.get("collection");
        assertThat(collection.get("sampled")).isEqualTo(true);
        assertThat(collection.get("complete")).isEqualTo(false);
    }

    @Test
    @DisplayName("falls back to the old collection node")
    void fallsBackToTheOldCollection() {
        Map<String, Object> upstream = new LinkedHashMap<>();
        upstream.put("collect_issues", nodeOutput(exactSummary(5,
                List.of(issue("1", "open", false), issue("2", "open", true)))));

        NodeContext context = NodeContext.builder()
                .nodeKey("compute_metrics")
                .config(Map.of("operation", "issue_metrics"))
                .upstreamOutputs(upstream)
                .build();

        Map<String, Object> metrics = invokeIssueMetrics(context);

        assertThat(metrics.get("openIssues")).isEqualTo(5L);
        // The pull requests are counted from the shared collection.
        assertThat(metrics.get("pullRequests")).isEqualTo(1L);
    }

    /**
     * Calls the issue metrics of the executor.
     *
     * <p>The method is private, so the test drives it through the public entry
     * point of the node.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeIssueMetrics(NodeContext context) {
        NodeResult result = executor.execute(context);
        assertThat(result.isSuccess()).isTrue();
        return (Map<String, Object>) result.getOutput();
    }
}
