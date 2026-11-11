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
 * A single physical execution attempt of a node.
 *
 * <p>Failures are valuable engineering data: keeping them separately from the logical node
 * execution makes it possible to analyse how often a node needed a retry and why.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("node_execution_attempt")
public class NodeExecutionAttempt {

    @TableId(type = IdType.INPUT)
    private Long id;

    private Long nodeExecutionId;
    private Integer attemptNo;

    private String status;

    private String workerId;
    private Long leaseToken;

    private String inputSnapshot;
    private String outputSnapshot;

    private String errorCode;
    private String errorMessage;

    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long durationMs;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
