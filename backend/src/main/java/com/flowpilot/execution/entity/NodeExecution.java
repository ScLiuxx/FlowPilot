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
 * The logical execution of a single node within a workflow execution.
 *
 * <p>Lease fields implement the worker failure recovery mechanism: a worker that holds an expired
 * lease must not be able to commit its result, which is enforced with the fencing token.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("node_execution")
public class NodeExecution {

    @TableId(type = IdType.INPUT)
    private Long id;

    private Long workflowExecutionId;
    private String nodeKey;
    private String nodeType;

    private String status;

    private String inputJson;
    private String outputJson;

    private Integer currentAttempt;
    private Integer maxAttempts;

    private String workerId;
    /** Monotonically increasing fencing token, incremented on every lease acquisition. */
    private Long leaseToken;
    private LocalDateTime leaseExpireAt;
    private LocalDateTime heartbeatAt;

    /** The moment the node may be retried, persisted so the delay survives a restart. */
    private LocalDateTime nextRetryAt;

    private String errorCode;
    private String errorMessage;

    private Long version;

    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
