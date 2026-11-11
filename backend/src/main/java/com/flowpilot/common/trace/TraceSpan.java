package com.flowpilot.common.trace;

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
 * A persisted span of one execution.
 *
 * <p>The span is written when it ends. The row keeps the chain queryable even if no collector
 * is running, which is what the acceptance criterion of the execution trace asks for.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("trace_span")
public class TraceSpan {

    @TableId(type = IdType.INPUT)
    private Long id;

    private String traceId;
    private String parentSpanId;
    private String spanId;
    private String spanName;

    private Long workflowExecutionId;
    private Long nodeExecutionId;

    private Long durationMs;
    private String status;

    private String attributes;

    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
