package com.flowpilot.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests of the timestamps of the API.
 *
 * <p>The backend stores its timestamps in UTC. A browser reads a timestamp without a zone as
 * local time, so the offset has to travel with the value. Otherwise the console shows the
 * execution eight hours in the past, and the running duration is eight hours too long while the
 * closed duration is right again.
 */
class TimestampsTest {

    @Test
    @DisplayName("a timestamp carries the UTC offset")
    void carriesTheUtcOffset() {
        String iso = Timestamps.toIso(LocalDateTime.of(2026, 11, 15, 10, 23, 45, 123_000_000));

        assertThat(iso).isEqualTo("2026-11-15T10:23:45.123Z");
        // The browser parses the value as the instant it names, not as a local time.
        assertThat(OffsetDateTime.parse(iso).toInstant())
                .isEqualTo(LocalDateTime.of(2026, 11, 15, 10, 23, 45, 123_000_000)
                        .toInstant(ZoneOffset.UTC));
    }

    @Test
    @DisplayName("a missing timestamp stays missing")
    void keepsNull() {
        assertThat(Timestamps.toIso(null)).isNull();
    }

    @Test
    @DisplayName("the millisecond precision is kept")
    void keepsMillisecondPrecision() {
        String iso = Timestamps.toIso(LocalDateTime.of(2026, 11, 15, 10, 23, 45, 1_000_000));

        assertThat(iso).endsWith(".001Z");
    }
}
