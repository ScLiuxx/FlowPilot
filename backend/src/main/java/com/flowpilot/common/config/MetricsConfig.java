package com.flowpilot.common.config;

import org.springframework.context.annotation.Configuration;

/**
 * The configuration of the metrics registry.
 *
 * <p>The durations are published as a histogram, so the dashboard can show a percentile instead
 * of an average. Without the histogram the registry only exposes the sum and the count, and a
 * percentile query has nothing to work with. The histogram is enabled per meter in
 * {@code FlowPilotMetrics}.
 */
@Configuration
public class MetricsConfig {
}
