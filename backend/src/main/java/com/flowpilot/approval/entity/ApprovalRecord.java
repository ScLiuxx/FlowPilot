package com.flowpilot.approval.entity;

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
 * A human decision that pauses a workflow.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("approval_record")
public class ApprovalRecord {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_CANCELED = "CANCELED";
    public static final String STATUS_EXPIRED = "EXPIRED";

    @TableId(type = IdType.INPUT)
    private Long id;

    private Long workflowExecutionId;
    private Long nodeExecutionId;

    private String approvalType;

    private String status;

    private String title;
    private String description;

    private String requestData;
    private String decisionData;

    private LocalDateTime requestedAt;
    private LocalDateTime decidedAt;

    private Long approverId;
    private String comment;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
