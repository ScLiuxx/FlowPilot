package com.flowpilot.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.flowpilot.common.util.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

/**
 * Reads the real state of an agent invocation from the agent service.
 *
 * <p>The architecture design asks for this ability, because the two databases do not share a
 * transaction: the agent may have written its checkpoint and even sent its result while the Java
 * service never saw the message. During a recovery the Java service therefore asks the agent
 * service what really happened instead of assuming the worst.
 */
@Slf4j
@Component
public class AgentResultClient {

    private final RestClient restClient;
    private final String internalToken;

    public AgentResultClient(RestClient.Builder builder,
                             @Value("${flowpilot.agent.service-url:http://localhost:8000}") String serviceUrl,
                             @Value("${flowpilot.agent.internal-token:}") String internalToken,
                             @Value("${flowpilot.agent.query-timeout-seconds:10}") long timeoutSeconds) {
        this.restClient = builder
                .baseUrl(serviceUrl)
                .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory() {{
                    setConnectTimeout((int) Duration.ofSeconds(timeoutSeconds).toMillis());
                    setReadTimeout((int) Duration.ofSeconds(timeoutSeconds).toMillis());
                }})
                .build();
        this.internalToken = internalToken;
    }

    /**
     * The real state of an invocation.
     *
     * @return the state, or {@code null} when the agent service does not know the invocation
     */
    public AgentExecutionState query(Long invocationId) {
        try {
            JsonNode body = restClient.get()
                    .uri("/internal/invocations/{id}/result", invocationId)
                    .header("Authorization", "Bearer " + internalToken)
                    .retrieve()
                    .body(JsonNode.class);
            if (body == null) {
                return null;
            }
            return new AgentExecutionState(
                    body.path("status").asText(null),
                    body.path("checkpointId").isMissingNode() ? null : body.path("checkpointId").asText(null),
                    body.path("result").isMissingNode() || body.path("result").isNull()
                            ? null : JsonUtils.toMap(body.path("result")),
                    body.path("error").isMissingNode() || body.path("error").isNull()
                            ? null : JsonUtils.toMap(body.path("error")));
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) {
                log.info("the agent service does not know the invocation {}", invocationId);
                return null;
            }
            log.warn("the agent service could not be asked about the invocation {}: {}",
                    invocationId, ex.getMessage());
            return null;
        } catch (Exception ex) {
            log.warn("the agent service could not be asked about the invocation {}: {}",
                    invocationId, ex.getMessage());
            return null;
        }
    }

    /**
     * The state the agent service reports.
     *
     * @param status      the status of the invocation
     * @param checkpointId the latest checkpoint of the agent thread
     * @param result      the result of a successful invocation
     * @param error       the error of a failed invocation
     */
    public record AgentExecutionState(String status,
                                      String checkpointId,
                                      Map<String, Object> result,
                                      Map<String, Object> error) {

        /** Whether the agent finished the invocation successfully. */
        public boolean succeeded() {
            return "SUCCEEDED".equals(status);
        }

        /** Whether the agent failed the invocation. */
        public boolean failed() {
            return "FAILED".equals(status);
        }

        /** Whether the agent is still working on the invocation. */
        public boolean running() {
            return "RUNNING".equals(status);
        }
    }
}
