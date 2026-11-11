package com.flowpilot.execution.service;

import com.flowpilot.common.id.SnowflakeIdGenerator;
import com.flowpilot.common.trace.TraceContext;
import com.flowpilot.common.util.JsonUtils;
import com.flowpilot.execution.entity.ExecutionEvent;
import com.flowpilot.execution.mapper.ExecutionEventMapper;
import com.flowpilot.execution.model.EventType;
import com.flowpilot.execution.mapper.WorkflowExecutionMapper;
import com.flowpilot.monitoring.sse.ExecutionEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Appends execution events.
 *
 * <p>The sequence number is allocated by locking the workflow execution row, which guarantees that
 * the sequence is unique inside one execution and strictly increasing. That sequence is reused as
 * the SSE event id, so a client that reconnects can ask for everything after the last id it saw.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionEventService {

    private final ExecutionEventMapper eventMapper;
    private final WorkflowExecutionMapper executionMapper;
    private final SnowflakeIdGenerator idGenerator;
    private final ExecutionEventPublisher eventPublisher;

    /**
     * Appends an event inside the caller's transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ExecutionEvent append(Long workflowExecutionId,
                                 Long nodeExecutionId,
                                 EventType eventType,
                                 Map<String, Object> data,
                                 Long operatorId) {
        long sequence = nextSequence(workflowExecutionId);
        ExecutionEvent event = ExecutionEvent.builder()
                .id(idGenerator.nextId())
                .workflowExecutionId(workflowExecutionId)
                .nodeExecutionId(nodeExecutionId)
                .eventType(eventType.name())
                .eventData(JsonUtils.toJson(data == null ? Map.of() : data))
                .sequenceNo(sequence)
                .operatorId(operatorId)
                .traceId(TraceContext.currentTraceId())
                .createdAt(LocalDateTime.now())
                .build();
        eventMapper.insert(event);
        // The notification is published after the insert. The row is not visible to other
        // transactions until the current one commits, so the subscribers pick it up a moment
        // later, which is harmless because the event list is the authoritative source.
        eventPublisher.publish(workflowExecutionId, sequence, eventType.name());
        return event;
    }

    /**
     * Allocates the next sequence number.
     *
     * <p>The workflow execution row is locked first, which serialises concurrent appends for the
     * same execution. The counter is then incremented and read back. Because the lock is held
     * until the transaction commits, no two appends can observe the same counter value.
     */
    private long nextSequence(Long workflowExecutionId) {
        executionMapper.lockEventSequence(workflowExecutionId);
        executionMapper.incrementEventSequence(workflowExecutionId);
        Long counter = executionMapper.selectById(workflowExecutionId).getEventSequence();
        return counter == null ? 1L : counter;
    }

    public List<ExecutionEvent> listAfter(Long workflowExecutionId, long afterSequence, int limit) {
        return eventMapper.findAfterSequence(workflowExecutionId, afterSequence, limit);
    }

    public List<ExecutionEvent> listAll(Long workflowExecutionId, int limit) {
        return eventMapper.findByExecutionId(workflowExecutionId, limit);
    }
}
