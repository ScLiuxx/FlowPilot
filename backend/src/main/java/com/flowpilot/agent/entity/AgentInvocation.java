package com.flowpilot.agent.entity;

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
 * Correlation record between a Java node execution and a LangGraph execution.
 *
 * <p>The {@code threadId} is derived from the invocation id and stays stable across retries and
 * resumes, so the agent service can always find the checkpoint of a previous attempt.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("agent_invocation")
public class AgentInvocation {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_WAITING_HUMAN = "WAITING_HUMAN";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELED = "CANCELED";

    @TableId(type = IdType.INPUT)
    private Long id;

    private String invocationNo;

    private Long workflowExecutionId;
    private Long nodeExecutionId;
    private Integer attemptNo;

    private String agentType;

    private String threadId;
    private String checkpointId;

    private String status;

    private String modelName;
    private Long tokenInput;
    private Long tokenOutput;

    private String errorCode;
    private String errorMessage;

    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
