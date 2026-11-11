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
 * One business execution of a workflow version.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("workflow_execution")
public class WorkflowExecution {

    @TableId(type = IdType.INPUT)
    private Long id;

    private Long workflowId;
    private Long workflowVersionId;
    private Long userId;

    private String executionNo;
    private String status;

    private String inputJson;
    private String outputJson;

    private Integer currentNodeCount;
    private Integer completedNodeCount;
    private Integer failedNodeCount;

    /** Monotonic event sequence, used as the SSE event id. */
    private Long eventSequence;

    /** Set when the user requested a cancellation, before the final CANCELED state. */
    private Integer cancelRequested;
    private String cancelReason;

    private String errorCode;
    private String errorMessage;

    /** Optimistic lock version. */
    private Long version;

    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
