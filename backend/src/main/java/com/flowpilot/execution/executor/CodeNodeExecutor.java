package com.flowpilot.execution.executor;

import com.flowpilot.execution.model.NodeType;
import com.flowpilot.messaging.protocol.ErrorCategory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic data processing.
 *
 * <p>The supported operations are deliberately small and side effect free. The node turns the
 * raw GitHub responses into the statistics the analysis agent expects, so the agent never has to
 * re-derive them and the numbers in the report stay reproducible.
 */
@Slf4j
@Component
public class CodeNodeExecutor implements NodeExecutor {

    @Override
    public NodeType supportType() {
        return NodeType.CODE;
    }

    @Override
    public NodeResult execute(NodeContext context) {
        String operation = String.valueOf(context.getConfig().getOrDefault("operation", ""));
        try {
            Map<String, Object> output;
            switch (operation) {
                case "repository_metrics" -> output = repositoryMetrics(context);
                case "activity_metrics" -> output = activityMetrics(context);
                case "issue_metrics" -> output = issueMetrics(context);
                case "release_metrics" -> output = releaseMetrics(context);
                case "all_metrics" -> output = allMetrics(context);
                case "normalize_inputs" -> output = normalizeInputs(context);
                case "build_report_input" -> output = buildReportInput(context);
                case "identity" -> output = new LinkedHashMap<>(context.getInput());
                default -> {
                    return NodeResult.failure("CODE_UNSUPPORTED_OPERATION", ErrorCategory.VALIDATION,
                            "Unsupported CODE operation: " + operation, false);
                }
            }
            return NodeResult.success(output);
        } catch (NumberFormatException ex) {
            return NodeResult.failure("CODE_INVALID_NUMBER", ErrorCategory.VALIDATION,
                    "The upstream data contained a value that is not a number", false);
        } catch (Exception ex) {
            log.warn("CODE node {} failed", context.getNodeKey(), ex);
            return NodeResult.failure("CODE_EXECUTION_ERROR", ErrorCategory.INTERNAL,
                    "The data processing step failed: " + ex.getMessage(), true);
        }
    }

    /**
     * Derives every metric in one pass.
     *
     * <p>The collection nodes run in parallel, so a single aggregation node turns their outputs
     * into the statistics the report and the agent need.
     */
    private Map<String, Object> allMetrics(NodeContext context) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("repository", repositoryMetrics(context));
        output.put("activity", activityMetrics(context));
        output.put("issues", issueMetrics(context));
        output.put("releases", releaseMetrics(context));
        // The scope of the data: the report states it, so a sample is never
        // presented as the full history of the repository.
        output.put("collection", collectionMeta(
                upstream(context, "collect_commits").get("body"),
                upstreamAny(context, "collect_open_issues", "collect_issues").get("body"),
                upstream(context, "collect_closed_issues").get("body"),
                upstream(context, "collect_pull_requests").get("body"),
                upstream(context, "collect_releases").get("body")));
        return output;
    }

    /**
     * Reads the repository payload and derives the basic project metrics.
     */
    private Map<String, Object> repositoryMetrics(NodeContext context) {
        Map<String, Object> payload = upstream(context, "collect_repository");
        Map<String, Object> body = asMap(payload.get("body"));
        // The HTTP node summarises every response the same way: an endpoint
        // that answers with an object holds exactly one item.
        List<Map<String, Object>> items = asList(body);
        if (!items.isEmpty()) {
            body = items.get(0);
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("fullName", body.get("full_name"));
        output.put("description", body.get("description"));
        output.put("stars", asInt(body.get("stargazers_count")));
        output.put("forks", asInt(body.get("forks_count")));
        output.put("openIssues", asInt(body.get("open_issues_count")));
        output.put("watchers", asInt(body.get("watchers_count")));
        output.put("primaryLanguage", body.get("language"));
        output.put("license", nested(body, "license", "spdx_id"));
        output.put("createdAt", body.get("created_at"));
        output.put("updatedAt", body.get("updated_at"));
        output.put("pushedAt", body.get("pushed_at"));
        output.put("defaultBranch", body.get("default_branch"));
        output.put("archived", Boolean.TRUE.equals(body.get("archived")));
        output.put("topics", body.get("topics"));
        return output;
    }

    /**
     * Derives the commit activity statistics of the last 30 days.
     *
     * <p>The number of commits is the number that was collected in this run: the
     * {@code totalPages} of the response tells whether more pages exist. The
     * metadata therefore states whether the number is complete.
     */
    private Map<String, Object> activityMetrics(NodeContext context) {
        Map<String, Object> payload = upstream(context, "collect_commits");
        Object body = payload.get("body");
        List<Map<String, Object>> commits = asList(body);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("totalCommits", collectedCount(body, commits));
        output.put("analyzedCommits", commits.size());
        output.put("recentCommits", countRecent(commits, 30));
        output.put("authors", countDistinctAuthors(commits));
        output.put("latestCommitAt", commits.isEmpty() ? null : latestCommitDate(commits));
        output.put("commitMessages", commits.stream()
                .limit(20)
                .map(commit -> nested(commit, "commit", "message"))
                .toList());
        output.put("collection", collectionMeta(body));
        return output;
    }

    /**
     * Derives the issue statistics.
     *
     * <p>The open issues, the closed issues and the pull requests are collected
     * separately: the issues endpoint only answers with the open issues by
     * default, so the closed ones would never show up in a single collection.
     * The pull requests are counted from the pull request collection, which
     * does not mix them with the issues.
     *
     * <p>The numbers are the number of items that were collected. They are the
     * totals of the repository only when the collection reached the last page;
     * the metadata states which of the two it is.
     */
    private Map<String, Object> issueMetrics(NodeContext context) {
        Map<String, Object> openPayload = upstreamAny(context, "collect_open_issues", "collect_issues");
        Map<String, Object> closedPayload = upstream(context, "collect_closed_issues");
        Map<String, Object> pullPayload = upstream(context, "collect_pull_requests");

        Object openBody = openPayload.get("body");
        Object closedBody = closedPayload.get("body");
        Object pullBody = pullPayload.get("body");

        List<Map<String, Object>> openItems = asList(openBody);
        List<Map<String, Object>> closedItems = asList(closedBody);
        List<Map<String, Object>> pullItems = asList(pullBody);

        // The fallback collection (the old "collect_issues" node) holds the
        // open issues and the pull requests together.
        long pullRequests = pullPayload.isEmpty()
                ? openItems.stream().filter(issue -> issue.get("pull_request") != null).count()
                : collectedCount(pullBody, pullItems);

        List<Map<String, Object>> openIssues = openItems.stream()
                .filter(issue -> issue.get("pull_request") == null)
                .toList();
        List<Map<String, Object>> closedIssues = closedItems.stream()
                .filter(issue -> issue.get("pull_request") == null)
                .toList();
        if (closedItems.isEmpty() && closedPayload.isEmpty()) {
            // The fallback collection: the closed issues are the ones the
            // endpoint returned with the closed state.
            closedIssues = openItems.stream()
                    .filter(issue -> issue.get("pull_request") == null)
                    .filter(issue -> "closed".equals(issue.get("state")))
                    .toList();
        }

        long openCount = collectedCount(openBody, openIssues);
        long closedCount = collectedCount(closedBody, closedIssues);
        boolean exact = countIsExact(openBody) && countIsExact(closedBody) && countIsExact(pullBody);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("totalItems", openCount + closedCount);
        output.put("analyzedItems", openIssues.size() + closedIssues.size() + pullItems.size());
        output.put("totalIssues", openCount + closedCount);
        output.put("openIssues", openCount);
        output.put("closedIssues", closedCount);
        output.put("pullRequests", pullRequests);
        output.put("recentIssues", countRecent(openIssues, 30));
        output.put("openIssueTitles", openIssues.stream()
                .filter(issue -> "open".equals(issue.get("state")))
                .limit(20)
                .map(issue -> issue.get("title"))
                .toList());
        // The numbers are only exact when every collection reached its last page.
        output.put("countsExact", exact);
        output.put("collection", collectionMeta(openBody, closedBody, pullBody));
        return output;
    }

    /**
     * Derives the release statistics.
     */
    private Map<String, Object> releaseMetrics(NodeContext context) {
        Map<String, Object> payload = upstream(context, "collect_releases");
        Object body = payload.get("body");
        List<Map<String, Object>> releases = asList(body);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("totalReleases", collectedCount(body, releases));
        output.put("analyzedReleases", releases.size());
        output.put("latestVersion", releases.isEmpty() ? null : nested(releases.get(0), "tag_name"));
        output.put("latestPublishedAt", releases.isEmpty() ? null
                : nested(releases.get(0), "published_at"));
        output.put("recentReleases", releases.stream()
                .limit(10)
                .map(release -> Map.of(
                        "tag", String.valueOf(release.get("tag_name")),
                        "publishedAt", String.valueOf(release.get("published_at"))))
                .toList());
        output.put("daysSinceLastRelease", releases.isEmpty() ? null
                : daysSince((String) nested(releases.get(0), "published_at")));
        output.put("collection", collectionMeta(body));
        return output;
    }

    /**
     * Normalises the raw payloads into a compact structure the agent can consume.
     */
    private Map<String, Object> normalizeInputs(NodeContext context) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("repository", upstream(context, "collect_repository"));
        output.put("commits", upstream(context, "collect_commits"));
        output.put("issues", upstreamAny(context, "collect_open_issues", "collect_issues"));
        output.put("closedIssues", upstream(context, "collect_closed_issues"));
        output.put("pullRequests", upstream(context, "collect_pull_requests"));
        output.put("releases", upstream(context, "collect_releases"));
        return output;
    }

    /**
     * Builds the final input of the analysis agent from the statistics of the previous nodes.
     */
    private Map<String, Object> buildReportInput(NodeContext context) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("repository", upstream(context, "analyze_metrics"));
        output.put("metrics", context.getInput());
        return output;
    }

    private Map<String, Object> upstream(NodeContext context, String nodeKey) {
        Object value = context.getUpstreamOutputs().get(nodeKey);
        if (value == null) {
            return Map.of();
        }
        return asMap(value);
    }

    /**
     * Reads the first of several upstream nodes that exists.
     *
     * <p>The collection nodes were renamed when the open and the closed issues
     * were collected separately. The old node is still read as a fallback, so
     * an existing workflow keeps working.
     */
    private Map<String, Object> upstreamAny(NodeContext context, String... nodeKeys) {
        for (String nodeKey : nodeKeys) {
            Map<String, Object> value = upstream(context, nodeKey);
            if (!value.isEmpty()) {
                return value;
            }
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    /**
     * Reads the items of a summarised list response.
     *
     * <p>The HTTP node stores a bounded summary of a list response as {@code {count, items}}, so
     * the metrics are computed over the stored items while the count reflects the full response.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asList(Object value) {
        if (value instanceof List<?> list) {
            List<Map<String, Object>> result = new java.util.ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    result.add((Map<String, Object>) map);
                }
            }
            return result;
        }
        if (value instanceof Map<?, ?> map && map.get("items") instanceof List<?> items) {
            List<Map<String, Object>> result = new java.util.ArrayList<>();
            for (Object item : items) {
                if (item instanceof Map<?, ?> itemMap) {
                    result.add((Map<String, Object>) itemMap);
                }
            }
            return result;
        }
        return List.of();
    }

    /**
     * Reads the total count of a summarised list response.
     */
    private long totalCount(Object value, List<Map<String, Object>> items) {
        if (value instanceof Map<?, ?> map && map.get("count") instanceof Number number) {
            return number.longValue();
        }
        return items.size();
    }

    /**
     * The number of items a collection gathered.
     *
     * <p>The count of the summary is the number the endpoint reported: the exact
     * total for the search endpoints, the number of collected items for the
     * paginated ones.
     */
    private long collectedCount(Object body, List<Map<String, Object>> items) {
        return totalCount(body, items);
    }

    /**
     * Whether the count of a collection is the exact total.
     */
    private boolean countIsExact(Object body) {
        Map<String, Object> collection = collectionOf(body);
        return Boolean.TRUE.equals(collection.get("countExact"));
    }

    /**
     * The collection metadata of a summarised response.
     */
    private Map<String, Object> collectionOf(Object body) {
        if (body instanceof Map<?, ?> map && map.get("collection") instanceof Map<?, ?> collection) {
            return asMap(collection);
        }
        return Map.of();
    }

    /**
     * Merges the collection metadata of several responses.
     *
     * <p>The merged metadata states whether <em>every</em> collection was
     * complete, and it keeps the window the data was collected in.
     */
    private Map<String, Object> collectionMeta(Object... bodies) {
        Map<String, Object> merged = new LinkedHashMap<>();
        boolean complete = true;
        boolean exact = true;
        String collectedAt = null;
        long collected = 0;
        long analyzed = 0;
        Integer totalPages = null;
        for (Object body : bodies) {
            Map<String, Object> collection = collectionOf(body);
            if (collection.isEmpty()) {
                continue;
            }
            if (Boolean.FALSE.equals(collection.get("complete"))) {
                complete = false;
            }
            if (Boolean.FALSE.equals(collection.get("countExact"))) {
                exact = false;
            }
            Object at = collection.get("collectedAt");
            if (collectedAt == null && at != null) {
                collectedAt = String.valueOf(at);
            }
            collected += asLong(collection.get("collected"));
            analyzed += asLong(collection.get("analyzed"));
            Object pages = collection.get("totalPages");
            if (pages instanceof Number number) {
                totalPages = totalPages == null ? number.intValue() : Math.max(totalPages, number.intValue());
            }
        }
        merged.put("complete", complete);
        merged.put("countExact", exact);
        merged.put("sampled", !exact || !complete);
        merged.put("collected", collected);
        merged.put("analyzed", analyzed);
        if (collectedAt != null) {
            merged.put("collectedAt", collectedAt);
        }
        if (totalPages != null) {
            merged.put("totalPages", totalPages);
        }
        return merged;
    }

    private long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0L;
    }

    private Object nested(Map<String, Object> source, String... keys) {
        Map<String, Object> current = source;
        for (int i = 0; i < keys.length - 1; i++) {
            Object next = current.get(keys[i]);
            if (!(next instanceof Map<?, ?> map)) {
                return null;
            }
            current = asMap(map);
        }
        return current.get(keys[keys.length - 1]);
    }

    private long countRecent(List<Map<String, Object>> items, int days) {
        long cutoff = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000;
        return items.stream()
                .filter(item -> {
                    String date = (String) nested(item, "commit", "author", "date");
                    if (date == null) {
                        date = (String) item.get("created_at");
                    }
                    if (date == null) {
                        date = (String) item.get("updated_at");
                    }
                    if (date == null) {
                        return false;
                    }
                    try {
                        return java.time.Instant.parse(date).toEpochMilli() >= cutoff;
                    } catch (Exception ex) {
                        return false;
                    }
                })
                .count();
    }

    private long countDistinctAuthors(List<Map<String, Object>> commits) {
        return commits.stream()
                .map(commit -> nested(commit, "commit", "author", "name"))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .count();
    }

    /**
     * Reads the date of the most recent commit.
     */
    private Object latestCommitDate(List<Map<String, Object>> commits) {
        if (commits.isEmpty()) {
            return null;
        }
        Object commit = commits.get(0).get("commit");
        if (!(commit instanceof Map<?, ?> commitMap)) {
            return null;
        }
        return nested(asMap(commitMap), "author", "date");
    }

    private Long daysSince(String isoTimestamp) {
        if (isoTimestamp == null) {
            return null;
        }
        try {
            long millis = System.currentTimeMillis() - java.time.Instant.parse(isoTimestamp).toEpochMilli();
            return millis / (24L * 60 * 60 * 1000);
        } catch (Exception ex) {
            return null;
        }
    }

    private int asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
