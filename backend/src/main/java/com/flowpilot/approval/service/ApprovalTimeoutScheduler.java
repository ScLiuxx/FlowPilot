package com.flowpilot.approval.service;

import com.flowpilot.approval.entity.ApprovalRecord;
import com.flowpilot.approval.mapper.ApprovalRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Closes the approvals whose deadline elapsed.
 *
 * <p>The approval contract of the architecture design asks for a timeout handling. A workflow
 * that waits for a reviewer nobody answers would occupy a worker and a workflow slot forever, so
 * the approval is closed and the workflow either continues or fails.
 *
 * <p>The scanner is idempotent. The update of the approval is conditional on the approval still
 * being pending, so two scanners cannot expire the same approval twice.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApprovalTimeoutScheduler {

    private final ApprovalRecordMapper approvalMapper;
    private final ApprovalService approvalService;

    @Value("${flowpilot.approval.timeout-scan-interval-ms:60000}")
    private long scanIntervalMs;

    @Value("${flowpilot.approval.timeout-scan-enabled:true}")
    private boolean enabled;

    @Value("${flowpilot.approval.timeout-scan-batch-size:50}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${flowpilot.approval.timeout-scan-interval-ms:60000}")
    public void closeExpiredApprovals() {
        if (!enabled) {
            return;
        }
        List<ApprovalRecord> expired = approvalMapper.findExpired(LocalDateTime.now(), batchSize);
        for (ApprovalRecord approval : expired) {
            try {
                approvalService.expire(approval);
            } catch (Exception ex) {
                log.error("failed to expire approval {}", approval.getId(), ex);
            }
        }
    }
}
