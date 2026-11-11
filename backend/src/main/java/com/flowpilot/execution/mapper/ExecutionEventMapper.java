package com.flowpilot.execution.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowpilot.execution.entity.ExecutionEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ExecutionEventMapper extends BaseMapper<ExecutionEvent> {

    @Select("""
            SELECT * FROM execution_event
             WHERE workflow_execution_id = #{executionId}
               AND sequence_no > #{afterSequence}
             ORDER BY sequence_no
             LIMIT #{limit}
            """)
    List<ExecutionEvent> findAfterSequence(@Param("executionId") Long executionId,
                                            @Param("afterSequence") long afterSequence,
                                            @Param("limit") int limit);

    @Select("""
            SELECT * FROM execution_event
             WHERE workflow_execution_id = #{executionId}
             ORDER BY sequence_no
             LIMIT #{limit}
            """)
    List<ExecutionEvent> findByExecutionId(@Param("executionId") Long executionId,
                                            @Param("limit") int limit);

    @Select("SELECT MAX(sequence_no) FROM execution_event WHERE workflow_execution_id = #{executionId}")
    Long findMaxSequence(@Param("executionId") Long executionId);
}
