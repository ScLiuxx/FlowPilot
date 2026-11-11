package com.flowpilot.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowpilot.agent.entity.AgentInvocation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AgentInvocationMapper extends BaseMapper<AgentInvocation> {

    @Select("SELECT * FROM agent_invocation WHERE thread_id = #{threadId}")
    AgentInvocation findByThreadId(@Param("threadId") String threadId);

    @Select("SELECT * FROM agent_invocation WHERE node_execution_id = #{nodeExecutionId} ORDER BY attempt_no DESC")
    List<AgentInvocation> findByNodeExecutionId(@Param("nodeExecutionId") Long nodeExecutionId);

    @Select("SELECT * FROM agent_invocation WHERE workflow_execution_id = #{executionId} ORDER BY id")
    List<AgentInvocation> findByWorkflowExecutionId(@Param("executionId") Long executionId);
}
