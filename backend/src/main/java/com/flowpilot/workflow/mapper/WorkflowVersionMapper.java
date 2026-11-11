package com.flowpilot.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowpilot.workflow.entity.WorkflowVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface WorkflowVersionMapper extends BaseMapper<WorkflowVersion> {

    @Select("SELECT * FROM workflow_version WHERE workflow_id = #{workflowId} ORDER BY version DESC")
    List<WorkflowVersion> findByWorkflowId(@Param("workflowId") Long workflowId);

    @Select("SELECT * FROM workflow_version WHERE workflow_id = #{workflowId} AND version = #{version}")
    WorkflowVersion findByWorkflowIdAndVersion(@Param("workflowId") Long workflowId,
                                               @Param("version") Integer version);

    @Select("SELECT * FROM workflow_version WHERE workflow_id = #{workflowId} AND status = 'PUBLISHED' "
            + "ORDER BY version DESC LIMIT 1")
    WorkflowVersion findLatestPublished(@Param("workflowId") Long workflowId);

    @Select("SELECT COALESCE(MAX(version), 0) FROM workflow_version WHERE workflow_id = #{workflowId}")
    Integer findMaxVersion(@Param("workflowId") Long workflowId);
}
