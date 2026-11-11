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
        return output;
    }

    /**
     * Reads the repository payload and derives the basic project metrics.
     */
    private Map<String, Object> repositoryMetrics(NodeContext context) {
        Map<String, Object> payload = upstream(context, "collect_repository");
        Map<String, Object> body = asMap(payload.get("body"));
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
     */
    private Map<String, Object> activityMetrics(NodeContext context) {
        Map<String, Object> payload = upstream(context, "collect_commits");
        Object body = payload.get("body");
        List<Map<String, Object>> commits = asList(body);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("totalCommits", totalCount(body, commits));
        output.put("analyzedCommits", commits.size());
        output.put("recentCommits", countRecent(commits, 30));
        output.put("authors", countDistinctAuthors(commits));
        output.put("latestCommitAt", commits.isEmpty() ? null : latestCommitDate(commits));
        output.put("commitMessages", commits.stream()
                .limit(20)
                .map(commit -> nested(commit, "commit", "message"))
                .toList());
        return output;
    }

    /**
     * Derives the issue statistics, separating issues from pull requests.
     */
    private Map<String, Object> issueMetrics(NodeContext context) {
        Map<String, Object> payload = upstream(context, "collect_issues");
        Object body = payload.get("body");
        List<Map<String, Object>> issues = asList(body);
        long pullRequests = issues.stream().filter(issue -> issue.get("pull_request") != null).count();
        List<Map<String, Object>> pureIssues = issues.stream()
                .filter(issue -> issue.get("pull_request") == null)
                .toList();
        long closed = pureIssues.stream().filter(issue -> "closed".equals(issue.get("state"))).count();

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("totalItems", totalCount(body, issues));
        output.put("analyzedItems", issues.size());
        output.put("totalIssues", pureIssues.size());
        output.put("openIssues", pureIssues.size() - closed);
        output.put("closedIssues", closed);
        output.put("pullRequests", pullRequests);
        output.put("recentIssues", countRecent(pureIssues, 30));
        output.put("openIssueTitles", pureIssues.stream()
                .filter(issue -> "open".equals(issue.get("state")))
                .limit(20)
                .map(issue -> issue.get("title"))
                .toList());
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
        output.put("totalReleases", totalCount(body, releases));
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
        return output;
    }

    /**
     * Normalises the raw payloads into a compact structure the agent can consume.
     */
    private Map<String, Object> normalizeInputs(NodeContext context) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("repository", upstream(context, "collect_repository"));
        output.put("commits", upstream(context, "collect_commits"));
        output.put("issues", upstream(context, "collect_issues"));
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
