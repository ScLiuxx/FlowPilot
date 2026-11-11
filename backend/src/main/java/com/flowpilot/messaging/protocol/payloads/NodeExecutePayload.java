package com.flowpilot.messaging.protocol.payloads;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Payload of {@code NodeExecute}: instructs a worker to run one node.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NodeExecutePayload {

    private String nodeKey;
    private String nodeType;
    private Map<String, Object> config;
    private Map<String, Object> input;
    private Integer timeoutSeconds;
}
