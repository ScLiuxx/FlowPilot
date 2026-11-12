package com.flowpilot.report;

import com.flowpilot.execution.entity.WorkflowExecution;
import com.flowpilot.execution.mapper.WorkflowExecutionMapper;
import com.flowpilot.report.entity.AnalysisReport;
import com.flowpilot.report.mapper.AnalysisReportMapper;
import com.flowpilot.report.service.ReportService;
import com.flowpilot.common.id.SnowflakeIdGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests of the report list against a real MySQL instance.
 *
 * <p>A report belongs to the user of its execution. The list therefore has to
 * join the execution table and filter by its owner — filtering only on the
 * report table would expose the reports of every user.
 */
@SpringBootTest
@ActiveProfiles("test")
class ReportAuthorizationIntegrationTest {

    private static final Long OWNER = 91_001L;
    private static final Long OTHER = 91_002L;

    @Autowired
    private ReportService reportService;

    @Autowired
    private AnalysisReportMapper reportMapper;

    @Autowired
    private WorkflowExecutionMapper executionMapper;

    @Autowired
    private SnowflakeIdGenerator idGenerator;

    @Test
    @DisplayName("the list of a user only contains the reports of their own executions")
    @Transactional
    void theListOnlyContainsTheOwnReports() {
        Long ownReport = createReport(OWNER);
        Long foreignReport = createReport(OTHER);

        var own = reportService.page(1, 20, null, OWNER);
        assertThat(own.getRecords()).extracting(AnalysisReport::getId)
                .contains(ownReport)
                .doesNotContain(foreignReport);

        var foreign = reportService.page(1, 20, null, OTHER);
        assertThat(foreign.getRecords()).extracting(AnalysisReport::getId)
                .contains(foreignReport)
                .doesNotContain(ownReport);
    }

    @Test
    @DisplayName("an administrator sees every report")
    @Transactional
    void theAdministratorSeesEveryReport() {
        Long ownReport = createReport(OWNER);
        Long foreignReport = createReport(OTHER);

        var all = reportService.page(1, 20, null, null);
        assertThat(all.getRecords()).extracting(AnalysisReport::getId)
                .contains(ownReport, foreignReport);
    }

    @Test
    @DisplayName("the status filter works for a user and for an administrator")
    @Transactional
    void theStatusFilterWorks() {
        Long pending = createReport(OWNER, "PENDING_REVIEW");
        Long reviewed = createReport(OWNER, "APPROVED");

        var pendingOfUser = reportService.page(1, 20, "PENDING_REVIEW", OWNER);
        assertThat(pendingOfUser.getRecords()).extracting(AnalysisReport::getId)
                .contains(pending)
                .doesNotContain(reviewed);

        // The administrator path must not build an invalid statement.
        var pendingOfAdmin = reportService.page(1, 20, "PENDING_REVIEW", null);
        assertThat(pendingOfAdmin.getRecords()).extracting(AnalysisReport::getId)
                .contains(pending)
                .doesNotContain(reviewed);

        var all = reportService.page(1, 20, null, null);
        assertThat(all.getRecords()).extracting(AnalysisReport::getId)
                .contains(pending, reviewed);
    }

    @Test
    @DisplayName("the total counts the reports of the user only")
    @Transactional
    void theTotalCountsTheOwnReportsOnly() {
        long ownBefore = reportService.page(1, 20, null, OWNER).getTotal();
        long foreignBefore = reportService.page(1, 20, null, OTHER).getTotal();

        createReport(OWNER);
        createReport(OWNER);
        createReport(OTHER);

        // The total of a user only grows with the reports of their own executions.
        assertThat(reportService.page(1, 20, null, OWNER).getTotal() - ownBefore).isEqualTo(2L);
        assertThat(reportService.page(1, 20, null, OTHER).getTotal() - foreignBefore).isEqualTo(1L);
    }

    private Long createReport(Long userId) {
        return createReport(userId, "PENDING_REVIEW");
    }

    private Long createReport(Long userId, String status) {
        WorkflowExecution execution = WorkflowExecution.builder()
                .id(idGenerator.nextId())
                .workflowId(idGenerator.nextId())
                .workflowVersionId(idGenerator.nextId())
                .userId(userId)
                .executionNo("TEST-" + userId + "-" + System.nanoTime())
                .status("SUCCEEDED")
                .currentNodeCount(0)
                .completedNodeCount(0)
                .failedNodeCount(0)
                .eventSequence(0L)
                .cancelRequested(0)
                .version(0L)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        executionMapper.insert(execution);

        AnalysisReport report = AnalysisReport.builder()
                .id(idGenerator.nextId())
                .workflowExecutionId(execution.getId())
                .reportNo("RPT-TEST-" + execution.getId())
                .title("test report")
                .repositoryUrl("https://github.com/octocat/Hello-World")
                .reportType("GITHUB_DUE_DILIGENCE")
                .status(status)
                .summary("summary")
                .contentJson("{}")
                .markdownContent("# test")
                .contentHash("hash")
                .generatedAt(LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .build();
        reportMapper.insert(report);
        return report.getId();
    }
}
