package com.flowpilot.report.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowpilot.report.entity.AnalysisReport;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AnalysisReportMapper extends BaseMapper<AnalysisReport> {

    @Select("SELECT * FROM analysis_report WHERE workflow_execution_id = #{executionId} ORDER BY id DESC LIMIT 1")
    AnalysisReport findByWorkflowExecutionId(@Param("executionId") Long executionId);

    @Select("SELECT * FROM analysis_report ORDER BY created_at DESC LIMIT #{limit}")
    List<AnalysisReport> findRecent(@Param("limit") int limit);
}
