package com.flowpilot.monitoring.sse;

import com.flowpilot.common.util.Ids;
import com.flowpilot.common.util.JsonUtils;
import com.flowpilot.execution.entity.ExecutionEvent;
import com.flowpilot.execution.mapper.ExecutionEventMapper;
import com.flowpilot.execution.service.ExecutionEventService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Streams the execution events to the connected consoles.
 *
 * <p>The SSE stream is only a view. The authoritative state stays in the database, so a client
 * that reconnects can always catch up by asking for the events after the last sequence number it
 * saw.
 *
 * <p>Events produced on another backend instance arrive through the Redis pub/sub channel, so a
 * client sees the whole picture no matter which instance it is connected to.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SseEventBroadcaster {

    private static final long STREAM_TIMEOUT_MILLIS = 30 * 60 * 1000L;

    /** executionId -> the emitters that are currently listening. */
    private final Map<Long, Map<Long, SseEmitter>> emitters = new ConcurrentHashMap<>();

    private final ExecutionEventService eventService;
    private final ExecutionEventMapper eventMapper;
    private final RedisConnectionFactory redisConnectionFactory;

    private RedisMessageListenerContainer listenerContainer;

    @PostConstruct
    public void subscribeToNotifications() {
        try {
            listenerContainer = new RedisMessageListenerContainer();
            listenerContainer.setConnectionFactory(redisConnectionFactory);
            listenerContainer.addMessageListener((MessageListener) (message, pattern) -> {
                String body = new String(message.getBody(), StandardCharsets.UTF_8);
                String[] parts = body.split(":");
                if (parts.length < 2) {
                    return;
                }
                try {
                    Long executionId = Long.valueOf(parts[0]);
                    Long sequenceNo = Long.valueOf(parts[1]);
                    for (ExecutionEvent event : eventMapper.findAfterSequence(executionId, sequenceNo - 1, 100)) {
                        broadcast(event);
                    }
                } catch (NumberFormatException ex) {
                    log.debug("ignoring a malformed SSE notification: {}", body);
                }
            }, new org.springframework.data.redis.listener.ChannelTopic(
                    ExecutionEventPublisher.CHANNEL));
            listenerContainer.start();
        } catch (Exception ex) {
            log.warn("SSE notification listener could not be started: {}", ex.getMessage());
        }
    }

    @PreDestroy
    public void stopListener() {
        if (listenerContainer != null) {
            listenerContainer.stop();
        }
    }

    /**
     * Registers a new subscriber.
     *
     * @param afterSequence the last event the client already received
     */
    public SseEmitter subscribe(Long executionId, Long afterSequence) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        Map<Long, SseEmitter> subscribers =
                emitters.computeIfAbsent(executionId, key -> new ConcurrentHashMap<>());
        long emitterId = System.nanoTime();
        subscribers.put(emitterId, emitter);

        emitter.onCompletion(() -> remove(executionId, emitterId));
        emitter.onTimeout(() -> remove(executionId, emitterId));
        emitter.onError(error -> remove(executionId, emitterId));

        // The backlog goes first, so a reconnecting client does not miss anything.
        try {
            sendBacklog(emitter, executionId, afterSequence == null ? 0L : afterSequence);
        } catch (Exception ex) {
            log.warn("failed to send the SSE backlog for execution {}", executionId, ex);
        }
        return emitter;
    }

    /**
     * Sends the events that were persisted after the given sequence number.
     */
    public void sendBacklog(SseEmitter emitter, Long executionId, Long afterSequence) throws IOException {
        List<ExecutionEvent> events = eventService.listAfter(executionId, afterSequence, 500);
        for (ExecutionEvent event : events) {
            writeEvent(emitter, event);
        }
    }

    /**
     * Pushes a single event to every subscriber of the execution.
     */
    public void broadcast(ExecutionEvent event) {
        Map<Long, SseEmitter> subscribers = emitters.get(event.getWorkflowExecutionId());
        if (subscribers == null || subscribers.isEmpty()) {
            return;
        }
        for (Map.Entry<Long, SseEmitter> entry : subscribers.entrySet()) {
            try {
                writeEvent(entry.getValue(), event);
            } catch (IOException ex) {
                log.debug("subscriber of execution {} disconnected", event.getWorkflowExecutionId());
                remove(event.getWorkflowExecutionId(), entry.getKey());
            }
        }
    }

    private void writeEvent(SseEmitter emitter, ExecutionEvent event) throws IOException {
        emitter.send(SseEmitter.event()
                .id(String.valueOf(event.getSequenceNo()))
                .name(event.getEventType())
                .data(JsonUtils.toJson(toPayload(event))));
    }

    private Map<String, Object> toPayload(ExecutionEvent event) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("sequenceNo", event.getSequenceNo());
        payload.put("eventType", event.getEventType());
        payload.put("nodeExecutionId", Ids.of(event.getNodeExecutionId()));
        payload.put("eventData", JsonUtils.toMap(event.getEventData()));
        payload.put("createdAt", event.getCreatedAt() == null
                ? LocalDateTime.now().toString() : event.getCreatedAt().toString());
        return payload;
    }

    private void remove(Long executionId, Long emitterId) {
        Map<Long, SseEmitter> subscribers = emitters.get(executionId);
        if (subscribers == null) {
            return;
        }
        subscribers.remove(emitterId);
        if (subscribers.isEmpty()) {
            emitters.remove(executionId);
        }
    }

    /**
     * The number of currently connected subscribers, used by the monitoring overview.
     */
    public int subscriberCount() {
        return emitters.values().stream().mapToInt(Map::size).sum();
    }
}
