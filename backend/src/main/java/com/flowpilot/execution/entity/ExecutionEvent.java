package com.flowpilot.execution.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * An append only record of something that happened during an execution.
 *
 * <p>The authoritative current state lives in {@link WorkflowExecution} and
 * {@link NodeExecution}; this table keeps the history so the console can show a timeline and an
 * operator can audit what happened.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("execution_event")
public class ExecutionEvent {

    @TableId(type = IdType.INPUT)
    private Long id;

    private Long workflowExecutionId;
    private Long nodeExecutionId;

    private String eventType;
    private String eventData;

    /** Monotonic sequence within one workflow execution. */
    private Long sequenceNo;

    private Long operatorId;
    private String traceId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
