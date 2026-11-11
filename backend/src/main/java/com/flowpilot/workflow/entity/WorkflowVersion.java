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
 * An immutable snapshot of a workflow definition.
 *
 * <p>Once published the definition must not change. An execution always binds to one version, so
 * a running task keeps behaving exactly as it did when it was started, even if the workflow is
 * edited and republished in the meantime.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("workflow_version")
public class WorkflowVersion {

    @TableId(type = IdType.INPUT)
    private Long id;

    private Long workflowId;
    private Integer version;

    /** The canonical JSON definition of the DAG. */
    private String definitionJson;

    /** SHA-256 of the canonicalised definition. */
    private String definitionHash;

    private String status;
    private Long createdBy;
    private LocalDateTime publishedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
