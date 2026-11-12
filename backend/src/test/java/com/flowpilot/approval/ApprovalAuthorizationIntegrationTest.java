package com.flowpilot.approval;

import com.flowpilot.approval.entity.ApprovalRecord;
import com.flowpilot.approval.mapper.ApprovalRecordMapper;
import com.flowpilot.approval.service.ApprovalService;
import com.flowpilot.common.id.SnowflakeIdGenerator;
import com.flowpilot.execution.entity.WorkflowExecution;
import com.flowpilot.execution.mapper.WorkflowExecutionMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests of the approval list against a real MySQL instance.
 *
 * <p>An approval belongs to the execution it belongs to, and an execution belongs to the user
 * that started it. The list therefore has to join the execution table and filter by its owner —
 * filtering only on the approval table would expose the approvals of every user.
 */
@SpringBootTest
@ActiveProfiles("test")
class ApprovalAuthorizationIntegrationTest {

    private static final Long OWNER = 92_001L;
    private static final Long OTHER = 92_002L;

    @Autowired
    private ApprovalService approvalService;

    @Autowired
    private ApprovalRecordMapper approvalMapper;

    @Autowired
    private WorkflowExecutionMapper executionMapper;

    @Autowired
    private SnowflakeIdGenerator idGenerator;

    @Test
    @DisplayName("the list of a user only contains the approvals of their own executions")
    @Transactional
    void theListOnlyContainsTheOwnApprovals() {
        Long own = createApproval(OWNER);
        Long foreign = createApproval(OTHER);

        var ownList = approvalService.page(1, 20, null, OWNER);
        assertThat(ownList.getRecords()).extracting(ApprovalRecord::getId)
                .contains(own)
                .doesNotContain(foreign);

        var foreignList = approvalService.page(1, 20, null, OTHER);
        assertThat(foreignList.getRecords()).extracting(ApprovalRecord::getId)
                .contains(foreign)
                .doesNotContain(own);
    }

    @Test
    @DisplayName("an administrator sees every approval")
    @Transactional
    void theAdministratorSeesEveryApproval() {
        Long own = createApproval(OWNER);
        Long foreign = createApproval(OTHER);

        var all = approvalService.page(1, 20, null, null);
        assertThat(all.getRecords()).extracting(ApprovalRecord::getId)
                .contains(own, foreign);
    }

    @Test
    @DisplayName("the status filter works for a user and for an administrator")
    @Transactional
    void theStatusFilterWorks() {
        Long pending = createApproval(OWNER, ApprovalRecord.STATUS_PENDING);
        Long approved = createApproval(OWNER, ApprovalRecord.STATUS_APPROVED);

        var pendingOfUser = approvalService.page(1, 20, ApprovalRecord.STATUS_PENDING, OWNER);
        assertThat(pendingOfUser.getRecords()).extracting(ApprovalRecord::getId)
                .contains(pending)
                .doesNotContain(approved);

        // The administrator path must not build an invalid statement.
        var pendingOfAdmin = approvalService.page(1, 20, ApprovalRecord.STATUS_PENDING, null);
        assertThat(pendingOfAdmin.getRecords()).extracting(ApprovalRecord::getId)
                .contains(pending)
                .doesNotContain(approved);
    }

    @Test
    @DisplayName("the total counts the approvals of the user only")
    @Transactional
    void theTotalCountsTheOwnApprovalsOnly() {
        long ownBefore = approvalService.page(1, 20, null, OWNER).getTotal();
        long foreignBefore = approvalService.page(1, 20, null, OTHER).getTotal();

        createApproval(OWNER);
        createApproval(OWNER);
        createApproval(OTHER);

        assertThat(approvalService.page(1, 20, null, OWNER).getTotal() - ownBefore).isEqualTo(2L);
        assertThat(approvalService.page(1, 20, null, OTHER).getTotal() - foreignBefore).isEqualTo(1L);
    }

    private Long createApproval(Long userId) {
        return createApproval(userId, ApprovalRecord.STATUS_PENDING);
    }

    private Long createApproval(Long userId, String status) {
        WorkflowExecution execution = WorkflowExecution.builder()
                .id(idGenerator.nextId())
                .workflowId(idGenerator.nextId())
                .workflowVersionId(idGenerator.nextId())
                .userId(userId)
                .executionNo("TEST-" + userId + "-" + System.nanoTime())
                .status("WAITING_HUMAN")
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

        ApprovalRecord approval = ApprovalRecord.builder()
                .id(idGenerator.nextId())
                .workflowExecutionId(execution.getId())
                .nodeExecutionId(idGenerator.nextId())
                .approvalType("REPORT_REVIEW")
                .status(status)
                .title("test approval")
                .description("description")
                .requestData("{}")
                .requestedAt(LocalDateTime.now())
                .timeoutAction("FAIL")
                .createdAt(LocalDateTime.now())
                .build();
        approvalMapper.insert(approval);
        return approval.getId();
    }
}
