package com.flowpilot.execution.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;

/**
 * Retry configuration of a node.
 *
 * <p>The delay uses exponential backoff with jitter:
 * {@code min(maxDelay, initialDelay * multiplier^(attempt-1)) * U(0.8, 1.2)}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetryPolicy {

    private Integer maxAttempts;
    private Integer initialDelaySeconds;
    private Integer maxDelaySeconds;
    private Double multiplier;

    public static RetryPolicy defaultPolicy() {
        return RetryPolicy.builder()
                .maxAttempts(3)
                .initialDelaySeconds(2)
                .maxDelaySeconds(60)
                .multiplier(2.0)
                .build();
    }

    public int resolvedMaxAttempts() {
        return maxAttempts == null || maxAttempts < 1 ? 3 : maxAttempts;
    }

    public int resolvedInitialDelaySeconds() {
        return initialDelaySeconds == null || initialDelaySeconds < 0 ? 2 : initialDelaySeconds;
    }

    public int resolvedMaxDelaySeconds() {
        return maxDelaySeconds == null || maxDelaySeconds < 1 ? 60 : maxDelaySeconds;
    }

    public double resolvedMultiplier() {
        return multiplier == null || multiplier <= 1.0 ? 2.0 : multiplier;
    }

    /**
     * Computes the delay before the given attempt number is retried.
     *
     * @param attemptNo the attempt that just failed, starting at 1
     */
    public Duration delayFor(int attemptNo) {
        double raw = resolvedInitialDelaySeconds() * Math.pow(resolvedMultiplier(), Math.max(0, attemptNo - 1));
        long capped = (long) Math.min(resolvedMaxDelaySeconds(), raw);
        // Jitter keeps a fleet of workers from retrying at the same instant.
        double jitter = 0.8 + Math.random() * 0.4;
        return Duration.ofMillis(Math.max(0L, Math.round(capped * 1000 * jitter)));
    }
}
