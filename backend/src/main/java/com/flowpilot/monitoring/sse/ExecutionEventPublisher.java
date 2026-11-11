package com.flowpilot.monitoring.sse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Publishes the execution events to the SSE subscribers.
 *
 * <p>The events are already persisted in {@code execution_event}. This publisher only notifies the
 * subscribers that something changed, so a client that missed a notification can still catch up
 * through the {@code afterSequence} query.
 *
 * <p>Redis pub/sub is used to fan the notification out to every backend instance, so a client
 * connected to instance A also sees the events produced by instance B.
 */
@Slf4j
@Component
public class ExecutionEventPublisher {

    public static final String CHANNEL = "flowpilot:execution-events";

    private final StringRedisTemplate redisTemplate;

    public ExecutionEventPublisher(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Notifies the subscribers that a new event is available.
     */
    public void publish(Long executionId, Long sequenceNo, String eventType) {
        try {
            redisTemplate.convertAndSend(CHANNEL,
                    executionId + ":" + sequenceNo + ":" + eventType);
        } catch (Exception ex) {
            // The notification is a convenience. The client can always poll the event list, so a
            // failure here must not break the business transaction.
            log.warn("failed to publish the execution event notification: {}", ex.getMessage());
        }
    }
}
