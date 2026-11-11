package com.flowpilot.execution.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowpilot.execution.entity.NodeExecutionAttempt;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface NodeExecutionAttemptMapper extends BaseMapper<NodeExecutionAttempt> {

    @Select("SELECT * FROM node_execution_attempt WHERE node_execution_id = #{nodeExecutionId} ORDER BY attempt_no")
    List<NodeExecutionAttempt> findByNodeExecutionId(@Param("nodeExecutionId") Long nodeExecutionId);

    @Select("SELECT * FROM node_execution_attempt WHERE node_execution_id = #{nodeExecutionId} AND attempt_no = #{attemptNo}")
    NodeExecutionAttempt findByNodeAndAttempt(@Param("nodeExecutionId") Long nodeExecutionId,
                                              @Param("attemptNo") Integer attemptNo);
}
