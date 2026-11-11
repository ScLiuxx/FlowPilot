package com.flowpilot.workflow.entity;

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
 * Logical workflow. The executable configuration lives in {@link WorkflowVersion}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("workflow_definition")
public class WorkflowDefinition {

    @TableId(type = IdType.INPUT)
    private Long id;

    private String name;
    private String description;
    private String category;
    private Long ownerId;
    private Integer latestVersion;
    private String status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
