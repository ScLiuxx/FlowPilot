package com.flowpilot.execution.controller;

import com.flowpilot.execution.entity.NodeExecution;
import com.flowpilot.execution.entity.WorkflowExecution;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests of the timestamps the API returns.
 *
 * <p>The console shows how long an execution ran and when it started. The
 * backend stores its timestamps in UTC, so the values it sends have to carry
 * that offset: a browser reads a timestamp without one as local time and would
 * show the execution eight hours in the past, with a running duration that is
 * eight hours too long until the execution ends and both ends shift alike.
 */
@SpringBootTest
@ActiveProfiles("test")
class ApiTimestampTest {

    @Test
    @DisplayName("the timestamps of the API carry the UTC offset")
    void theTimestampsCarryTheUtcOffset() {
        var execution = WorkflowExecution.builder()
                .id(1L)
                .executionNo("FP-1")
                .status("RUNNING")
                .createdAt(LocalDateTime.of(2026, 11, 15, 10, 23, 45, 123_000_000))
                .startedAt(LocalDateTime.of(2026, 11, 15, 10, 23, 45, 123_000_000))
                .build();

        var response = ExecutionController.toResponse(execution);
        String text = response.getCreatedAt();

        assertThat(text)
                .as("the timestamp must name its offset")
                .endsWith("Z");
        // The value names an instant, so a browser reads it without a guess.
        assertThat(OffsetDateTime.parse(text).getOffset().getTotalSeconds()).isZero();
        assertThat(OffsetDateTime.parse(text).getNano() % 1_000_000)
                .as("the milliseconds are kept")
                .isLessThan(1_000_000);
    }

    @Test
    @DisplayName("the running duration and the closed duration agree")
    void theRunningAndTheClosedDurationAgree() {
        var started = LocalDateTime.of(2026, 11, 15, 10, 23, 45, 123_000_000);
        var finished = LocalDateTime.of(2026, 11, 15, 10, 30, 0, 123_000_000);

        var node = NodeExecution.builder()
                .id(1L)
                .nodeKey("analyze_repository")
                .nodeType("AGENT")
                .status("SUCCEEDED")
                .startedAt(started)
                .finishedAt(finished)
                .build();

        var response = ExecutionController.toResponse(node);

        // The browser reads both ends the same way, so the duration it shows
        // is the duration the backend measured.
        assertThat(response.getDurationMs()).isEqualTo(375_000L);
        assertThat(response.getStartedAt()).endsWith("Z");
        assertThat(response.getFinishedAt()).endsWith("Z");
    }
}
