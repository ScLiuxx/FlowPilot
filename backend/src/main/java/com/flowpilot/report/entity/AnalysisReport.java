package com.flowpilot.report.entity;

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
 * The technical due diligence report produced at the end of a workflow execution.
 *
 * <p>The report status is tracked separately from the workflow status: a workflow can succeed
 * while the report is still waiting for a review.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("analysis_report")
public class AnalysisReport {

    public static final String STATUS_GENERATING = "GENERATING";
    public static final String STATUS_PENDING_REVIEW = "PENDING_REVIEW";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_FAILED = "FAILED";

    @TableId(type = IdType.INPUT)
    private Long id;

    private Long workflowExecutionId;

    private String reportNo;
    private String title;

    private String repositoryUrl;

    private String reportType;
    private String status;

    private String summary;

    private String contentJson;
    private String markdownContent;

    private String storageUrl;
    private String contentHash;

    private LocalDateTime generatedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
