package com.flowpilot.workflow.definition;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RetryPolicyDefinition {

    private Integer maxAttempts;
    private Integer initialDelaySeconds;
    private Integer maxDelaySeconds;
    private Double multiplier;
}
