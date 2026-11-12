package com.flowpilot.report.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flowpilot.common.exception.BizException;
import com.flowpilot.common.id.SnowflakeIdGenerator;
import com.flowpilot.common.result.ErrorCode;
import com.flowpilot.common.result.PageResult;
import com.flowpilot.common.util.Ids;
import com.flowpilot.common.util.JsonUtils;
import com.flowpilot.execution.entity.NodeExecution;
import com.flowpilot.execution.entity.WorkflowExecution;
import com.flowpilot.execution.mapper.NodeExecutionMapper;
import com.flowpilot.execution.mapper.WorkflowExecutionMapper;
import com.flowpilot.execution.model.EventType;
import com.flowpilot.execution.service.ExecutionEventService;
import com.flowpilot.report.entity.AnalysisReport;
import com.flowpilot.report.mapper.AnalysisReportMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the technical due diligence report.
 *
 * <p>The report is assembled from the outputs of the workflow nodes, so every number in it can be
 * traced back to the node that produced it. The source of the data and the time of the analysis
 * are recorded as required by the product requirements.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    public static final String REPORT_TYPE = "GITHUB_DUE_DILIGENCE";

    private static final DateTimeFormatter REPORT_NO_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final AnalysisReportMapper reportMapper;
    private final WorkflowExecutionMapper executionMapper;
    private final NodeExecutionMapper nodeExecutionMapper;
    private final ExecutionEventService eventService;
    private final SnowflakeIdGenerator idGenerator;

    /**
     * Generates the report of a finished execution.
     */
    @Transactional
    public AnalysisReport generate(Long workflowExecutionId) {
        WorkflowExecution execution = executionMapper.selectById(workflowExecutionId);
        if (execution == null) {
            throw new BizException(ErrorCode.EX_NOT_FOUND);
        }
        AnalysisReport existing = reportMapper.findByWorkflowExecutionId(workflowExecutionId);
        if (existing != null) {
            log.debug("a report already exists for execution {}", workflowExecutionId);
            return existing;
        }

        List<NodeExecution> nodes = nodeExecutionMapper.findByWorkflowExecutionId(workflowExecutionId);
        Map<String, Map<String, Object>> outputs = new LinkedHashMap<>();
        for (NodeExecution node : nodes) {
            if (node.getOutputJson() != null) {
                outputs.put(node.getNodeKey(), JsonUtils.toMap(node.getOutputJson()));
            }
        }

        Map<String, Object> content = buildContent(execution, outputs);
        String markdown = renderMarkdown(execution, content);
        String hash = JsonUtils.sha256Hex(JsonUtils.canonicalize(content));

        Map<String, Object> input = JsonUtils.toMap(execution.getInputJson());
        String repositoryUrl = String.valueOf(input.getOrDefault("repositoryUrl", ""));

        AnalysisReport report = AnalysisReport.builder()
                .id(idGenerator.nextId())
                .workflowExecutionId(workflowExecutionId)
                .reportNo(buildReportNo())
                .title(buildTitle(input))
                .repositoryUrl(repositoryUrl.isBlank() ? null : repositoryUrl)
                .reportType(REPORT_TYPE)
                .status(AnalysisReport.STATUS_PENDING_REVIEW)
                .summary(String.valueOf(content.get("summary")))
                .contentJson(JsonUtils.toJson(content))
                .markdownContent(markdown)
                .contentHash(hash)
                .generatedAt(LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .build();
        reportMapper.insert(report);

        eventService.append(workflowExecutionId, null, EventType.REPORT_GENERATED,
                Map.of("reportId", Ids.of(report.getId()), "reportNo", report.getReportNo()), null);
        log.info("report generated reportId={} executionId={}", report.getId(), workflowExecutionId);
        return report;
    }

    /**
     * Assembles the structured sections of the report.
     */
    private Map<String, Object> buildContent(WorkflowExecution execution,
                                             Map<String, Map<String, Object>> outputs) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("reportNo", buildReportNo());
        content.put("generatedAt", LocalDateTime.now().toString());
        content.put("executionNo", execution.getExecutionNo());
        content.put("dataSources", List.of("GitHub REST API"));

        Map<String, Object> repository = firstNonEmpty(outputs, "repository", "repository_metrics");
        Map<String, Object> activity = firstNonEmpty(outputs, "activity", "activity_metrics");
        Map<String, Object> issues = firstNonEmpty(outputs, "issues", "issue_metrics");
        Map<String, Object> releases = firstNonEmpty(outputs, "releases", "release_metrics");

        // The aggregation node groups the four metric groups in one output.
        Map<String, Object> metrics = outputs.getOrDefault("compute_metrics", Map.of());
        if (metrics.containsKey("repository")) {
            repository = asMap(metrics.get("repository"));
            activity = asMap(metrics.get("activity"));
            issues = asMap(metrics.get("issues"));
            releases = asMap(metrics.get("releases"));
        }

        Map<String, Object> analysis = outputs.getOrDefault("analyze_repository",
                outputs.getOrDefault("analysis", Map.of()));

        content.put("repository", repository);
        content.put("activity", activity);
        content.put("issues", issues);
        content.put("releases", releases);
        content.put("analysis", analysis);
        // The scope of the data: the report states it, so that a sample is
        // never presented as the full history of the repository.
        content.put("collection", collectionScope(metrics, issues, activity, releases));

        content.put("summary", buildSummary(repository, activity, issues, releases, analysis));
        content.put("riskLevel", assessRiskLevel(activity, issues, releases, analysis, repository));
        content.put("productionReady", assessProductionReadiness(activity, issues, releases, analysis));
        return content;
    }

    private Map<String, Object> firstNonEmpty(Map<String, Map<String, Object>> outputs, String... keys) {
        for (String key : keys) {
            Map<String, Object> value = outputs.get(key);
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return Map.of();
    }

    /**
     * Collects the scope of the data from the metric groups.
     *
     * <p>The aggregation node already merged the metadata of the collections;
     * a workflow without it falls back to the metadata of the single groups.
     */
    private Map<String, Object> collectionScope(Map<String, Object> metrics,
                                                Map<String, Object> issues,
                                                Map<String, Object> activity,
                                                Map<String, Object> releases) {
        Map<String, Object> scope = asMap(metrics.get("collection"));
        if (!scope.isEmpty()) {
            return scope;
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        boolean complete = true;
        boolean exact = true;
        String collectedAt = null;
        for (Map<String, Object> group : List.of(issues, activity, releases)) {
            Map<String, Object> collection = asMap(group.get("collection"));
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
        }
        merged.put("complete", complete);
        merged.put("countExact", exact);
        merged.put("sampled", !exact || !complete);
        if (collectedAt != null) {
            merged.put("collectedAt", collectedAt);
        }
        return merged;
    }

    private String buildSummary(Map<String, Object> repository, Map<String, Object> activity,
                                Map<String, Object> issues, Map<String, Object> releases,
                                Map<String, Object> analysis) {
        StringBuilder summary = new StringBuilder();
        String fullName = String.valueOf(repository.getOrDefault("fullName", "unknown"));
        summary.append("项目 ").append(fullName);

        Object stars = repository.get("stars");
        if (stars != null) {
            summary.append("，获得 ").append(stars).append(" 个 Star");
        }
        Object recentCommits = activity.get("recentCommits");
        if (recentCommits != null) {
            summary.append("，最近 30 天提交 ").append(recentCommits).append(" 次");
        }
        Object openIssues = issues.get("openIssues");
        if (openIssues != null) {
            summary.append("，本次采集到 ").append(openIssues).append(" 个未关闭 Issue");
            if (Boolean.FALSE.equals(issues.get("countsExact"))) {
                summary.append("（抽样，非全量）");
            }
        }
        Object latestVersion = releases.get("latestVersion");
        if (latestVersion != null) {
            summary.append("，最新版本 ").append(latestVersion);
        }
        summary.append("。");

        Object conclusion = analysis.get("conclusion");
        if (conclusion != null) {
            summary.append(" ").append(conclusion);
        }
        return summary.toString();
    }

    /**
     * Derives a coarse risk level from the collected metrics.
     *
     * <p>The number of open issues comes from the repository metadata, which
     * reports the exact total. The collected issues are a sample and must not
     * decide the risk level.
     */
    private String assessRiskLevel(Map<String, Object> activity, Map<String, Object> issues,
                                   Map<String, Object> releases, Map<String, Object> analysis,
                                   Map<String, Object> repository) {
        Object explicit = analysis.get("riskLevel");
        if (explicit != null) {
            return String.valueOf(explicit);
        }
        int score = 0;
        long recentCommits = asLong(activity.get("recentCommits"));
        Long daysSinceRelease = releases.get("daysSinceLastRelease") == null
                ? null : asLong(releases.get("daysSinceLastRelease"));
        long openIssues = asLong(repository.get("openIssues"));

        if (recentCommits == 0) {
            score += 2;
        }
        if (daysSinceRelease != null && daysSinceRelease > 365) {
            score += 2;
        } else if (daysSinceRelease != null && daysSinceRelease > 180) {
            score += 1;
        }
        if (openIssues > 100) {
            score += 1;
        }
        if (score >= 4) {
            return "HIGH";
        }
        if (score >= 2) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private boolean assessProductionReadiness(Map<String, Object> activity, Map<String, Object> issues,
                                              Map<String, Object> releases, Map<String, Object> analysis) {
        Object explicit = analysis.get("productionReady");
        if (explicit instanceof Boolean ready) {
            return ready;
        }
        long recentCommits = asLong(activity.get("recentCommits"));
        Object archived = analysis.get("archived");
        if (Boolean.TRUE.equals(archived)) {
            return false;
        }
        // A project without recent commits is not actively maintained, so it is not a safe
        // dependency for a production system.
        return recentCommits > 0;
    }

    /**
     * Renders the report as Markdown.
     */
    private String renderMarkdown(WorkflowExecution execution, Map<String, Object> content) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# ").append(content.get("summary") == null ? "技术尽调报告" : "技术尽调报告")
                .append("\n\n");
        markdown.append("- 执行编号：").append(execution.getExecutionNo()).append("\n");
        markdown.append("- 生成时间：").append(content.get("generatedAt")).append("\n");
        markdown.append("- 数据来源：").append(String.join("、",
                (List<String>) content.getOrDefault("dataSources", List.of()))).append("\n");
        markdown.append("- 风险等级：").append(content.get("riskLevel")).append("\n");
        markdown.append("- 是否适合生产环境：")
                .append(Boolean.TRUE.equals(content.get("productionReady")) ? "是" : "否").append("\n\n");

        Map<String, Object> repository = asMap(content.get("repository"));
        if (!repository.isEmpty()) {
            markdown.append("## 1. 项目基本信息\n\n");
            markdown.append("| 项目 | 值 |\n|---|---|\n");
            markdown.append("| 名称 | ").append(repository.get("fullName")).append(" |\n");
            markdown.append("| 描述 | ").append(repository.get("description")).append(" |\n");
            markdown.append("| Star | ").append(repository.get("stars")).append(" |\n");
            markdown.append("| Fork | ").append(repository.get("forks")).append(" |\n");
            markdown.append("| 主要语言 | ").append(repository.get("primaryLanguage")).append(" |\n");
            markdown.append("| 许可证 | ").append(repository.get("license")).append(" |\n");
            markdown.append("| 最后推送 | ").append(repository.get("pushedAt")).append(" |\n\n");
        }

        Map<String, Object> activity = asMap(content.get("activity"));
        if (!activity.isEmpty()) {
            markdown.append("## 2. 最近 30 天提交情况\n\n");
            markdown.append("- 本次采集的提交数：").append(activity.get("totalCommits")).append("\n");
            markdown.append("- 近 30 天提交（采集范围内）：").append(activity.get("recentCommits")).append("\n");
            markdown.append("- 贡献者数量：").append(activity.get("authors")).append("\n");
            markdown.append("- 最近提交时间：").append(activity.get("latestCommitAt")).append("\n\n");
        }

        Map<String, Object> issues = asMap(content.get("issues"));
        if (!issues.isEmpty()) {
            markdown.append("## 3. Issue 与 Pull Request 活跃度\n\n");
            // The repository metadata carries the exact number of open issues
            // and pull requests; the collections are samples of the history.
            Object reported = repository.get("openIssues");
            if (reported != null) {
                markdown.append("- 未关闭 Issue 与 Pull Request（仓库元数据）：")
                        .append(reported).append("\n");
            }
            markdown.append("- 本次采集的 Issue 总数：").append(issues.get("totalIssues")).append("\n");
            markdown.append("- 本次采集的未关闭 Issue：").append(issues.get("openIssues")).append("\n");
            markdown.append("- 本次采集的已关闭 Issue：").append(issues.get("closedIssues")).append("\n");
            markdown.append("- 本次采集的 Pull Request：").append(issues.get("pullRequests")).append("\n");
            markdown.append("- 近 30 天新增 Issue（采集范围内）：").append(issues.get("recentIssues"))
                    .append("\n\n");
        }

        Map<String, Object> releases = asMap(content.get("releases"));
        if (!releases.isEmpty()) {
            markdown.append("## 4. 版本发布情况\n\n");
            markdown.append("- 本次采集的发布版本数：").append(releases.get("totalReleases")).append("\n");
            markdown.append("- 最新版本：").append(releases.get("latestVersion")).append("\n");
            markdown.append("- 最新发布时间：").append(releases.get("latestPublishedAt")).append("\n");
            markdown.append("- 距今天数：").append(releases.get("daysSinceLastRelease")).append("\n\n");
        }

        appendDataScope(markdown, content);

        Map<String, Object> analysis = asMap(content.get("analysis"));
        if (!analysis.isEmpty()) {
            markdown.append("## 5. 智能分析结论\n\n");
            appendSection(markdown, analysis, "architectureAnalysis", "5.1 技术架构分析");
            appendSection(markdown, analysis, "maintenanceAnalysis", "5.2 项目维护状况分析");
            appendSection(markdown, analysis, "riskAnalysis", "5.3 潜在技术风险分析");
            appendSection(markdown, analysis, "recommendation", "5.4 生产环境适用性评估");
        }

        markdown.append("\n---\n\n");
        markdown.append("本报告由 FlowPilot 智能工作流与 Agent 协同执行平台自动生成。\n");
        return markdown.toString();
    }

    private void appendSection(StringBuilder markdown, Map<String, Object> analysis,
                               String key, String title) {
        Object value = analysis.get(key);
        if (value == null) {
            return;
        }
        markdown.append("### ").append(title).append("\n\n");
        if (value instanceof List<?> list) {
            list.forEach(item -> markdown.append("- ").append(item).append("\n"));
        } else {
            markdown.append(String.valueOf(value)).append("\n");
        }
        markdown.append("\n");
    }

    /**
     * States the scope of the collected data.
     *
     * <p>The numbers of a report are only meaningful together with the question
     * whether they cover the whole repository or only the collected sample. A
     * sample must never be presented as the full history.
     */
    private void appendDataScope(StringBuilder markdown, Map<String, Object> content) {
        Map<String, Object> collection = asMap(content.get("collection"));
        if (collection.isEmpty()) {
            return;
        }
        markdown.append("## 数据采集说明\n\n");
        markdown.append("- 采集时间：").append(collection.getOrDefault("collectedAt", "未知"))
                .append("\n");
        boolean exact = Boolean.TRUE.equals(collection.get("countExact"));
        boolean complete = Boolean.TRUE.equals(collection.get("complete"));
        markdown.append("- 统计口径：")
                .append(exact ? "精确总数（GitHub 直接返回的匹配总数）" : "本次采集数量（分页采样）")
                .append("\n");
        markdown.append("- 是否完整采集：").append(complete ? "是" : "否（仅采集了部分数据）")
                .append("\n");
        markdown.append("- 是否为抽样结果：")
                .append(Boolean.TRUE.equals(collection.get("sampled")) ? "是" : "否").append("\n");
        Object pages = collection.get("totalPages");
        if (pages != null) {
            markdown.append("- 数据集总页数：").append(pages).append("\n");
        }
        markdown.append("\n");
    }

    private String buildTitle(Map<String, Object> input) {
        Object url = input.get("repositoryUrl");
        if (url == null || String.valueOf(url).isBlank()) {
            return "技术尽调报告";
        }
        String text = String.valueOf(url);
        int index = text.indexOf("github.com/");
        if (index >= 0) {
            String repo = text.substring(index + "github.com/".length());
            if (repo.endsWith(".git")) {
                repo = repo.substring(0, repo.length() - 4);
            }
            return repo + " 技术尽调报告";
        }
        return "技术尽调报告";
    }

    private String buildReportNo() {
        return "RPT-" + LocalDateTime.now().format(REPORT_NO_FORMAT) + "-"
                + String.format("%06d", Math.abs(idGenerator.nextId() % 1_000_000));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    public AnalysisReport get(Long reportId) {
        AnalysisReport report = reportMapper.selectById(reportId);
        if (report == null) {
            throw new BizException(ErrorCode.RP_NOT_FOUND);
        }
        return report;
    }

    /**
     * The reports the user may see.
     *
     * <p>A report belongs to the user of its execution. The list therefore joins the execution
     * table and filters by its owner, so a user never sees the report of somebody else. An
     * administrator sees all of them.
     *
     * @param userId the owner of the executions, {@code null} returns the reports of everybody
     */
    public PageResult<AnalysisReport> page(int page, int size, String status, Long userId) {
        long total = reportMapper.countByOwner(userId, status);
        List<AnalysisReport> records = reportMapper.findByOwner(userId, status, size, (page - 1) * size);
        return PageResult.of(total, page, size, records);
    }

    public List<AnalysisReport> recent(int limit) {
        return reportMapper.findRecent(limit);
    }

    /**
     * Updates the review status of a report.
     */
    @Transactional
    public AnalysisReport updateStatus(Long reportId, String status) {
        AnalysisReport report = get(reportId);
        report.setStatus(status);
        reportMapper.updateById(report);
        return report;
    }
}
