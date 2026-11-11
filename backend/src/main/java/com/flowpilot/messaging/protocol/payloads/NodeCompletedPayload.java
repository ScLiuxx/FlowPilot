package com.flowpilot.messaging.protocol.payloads;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Payload of {@code NodeCompleted}. The scheduler does not trust the carried result, it reads the
 * authoritative state from the database instead.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NodeCompletedPayload {

    private String nodeKey;
    private Map<String, Object> result;
}
