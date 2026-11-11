package com.flowpilot.execution.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests of the exponential backoff with jitter.
 */
class RetryPolicyTest {

    @Test
    @DisplayName("grows the delay exponentially and caps it")
    void growsExponentiallyAndCaps() {
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(6)
                .initialDelaySeconds(2)
                .maxDelaySeconds(60)
                .multiplier(2.0)
                .build();

        // The jitter is between 0.8 and 1.2, so the bounds are checked instead of the exact value.
        assertThat(policy.delayFor(1)).isBetween(Duration.ofSeconds(1), Duration.ofSeconds(3));
        assertThat(policy.delayFor(2)).isBetween(Duration.ofSeconds(3), Duration.ofSeconds(5));
        assertThat(policy.delayFor(3)).isBetween(Duration.ofSeconds(6), Duration.ofSeconds(10));
        // The cap is 60 seconds, the jitter may push it slightly above.
        assertThat(policy.delayFor(10)).isBetween(Duration.ofSeconds(48), Duration.ofSeconds(72));
    }

    @Test
    @DisplayName("uses the defaults when nothing is configured")
    void usesDefaults() {
        RetryPolicy policy = RetryPolicy.defaultPolicy();

        assertThat(policy.resolvedMaxAttempts()).isEqualTo(3);
        assertThat(policy.resolvedInitialDelaySeconds()).isEqualTo(2);
        assertThat(policy.resolvedMaxDelaySeconds()).isEqualTo(60);
        assertThat(policy.resolvedMultiplier()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("never returns a negative delay")
    void neverReturnsNegativeDelay() {
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(3)
                .initialDelaySeconds(0)
                .maxDelaySeconds(60)
                .multiplier(2.0)
                .build();

        assertThat(policy.delayFor(1)).isGreaterThanOrEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("adds jitter so a fleet of workers does not retry at the same moment")
    void addsJitter() {
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(3)
                .initialDelaySeconds(10)
                .maxDelaySeconds(60)
                .multiplier(2.0)
                .build();

        Duration first = policy.delayFor(1);
        Duration second = policy.delayFor(1);

        // The jitter makes the two delays differ in almost every case.
        assertThat(first).isNotEqualTo(second);
    }
}
