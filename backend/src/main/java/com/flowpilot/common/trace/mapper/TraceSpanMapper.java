package com.flowpilot.common.trace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowpilot.common.trace.TraceSpan;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TraceSpanMapper extends BaseMapper<TraceSpan> {

    /**
     * The spans of one trace, oldest first.
     */
    @Select("""
            SELECT * FROM trace_span
             WHERE trace_id = #{traceId}
             ORDER BY started_at, id
            """)
    List<TraceSpan> findByTraceId(@Param("traceId") String traceId);

    /**
     * The spans of one workflow execution, oldest first.
     */
    @Select("""
            SELECT * FROM trace_span
             WHERE workflow_execution_id = #{executionId}
             ORDER BY started_at, id
            """)
    List<TraceSpan> findByWorkflowExecutionId(@Param("executionId") Long executionId);
}
