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

    /**
     * The tokens of the invocations of the executions of one user.
     *
     * <p>The owner is {@code null} for the administrator, who sees the invocations of everybody.
     */
    @Select("""
            <script>
            SELECT COALESCE(SUM(i.token_input), 0) AS token_input,
                   COALESCE(SUM(i.token_output), 0) AS token_output
              FROM agent_invocation i
              JOIN workflow_execution e ON e.id = i.workflow_execution_id
             WHERE 1 = 1
               <if test="userId != null">
                 AND e.user_id = #{userId}
               </if>
            </script>
            """)
    TokenSum sumTokensByOwner(@Param("userId") Long userId);

    /**
     * The sum of the tokens of a group of invocations.
     */
    class TokenSum {
        private Long tokenInput;
        private Long tokenOutput;

        public Long getTokenInput() {
            return tokenInput;
        }

        public void setTokenInput(Long tokenInput) {
            this.tokenInput = tokenInput;
        }

        public Long getTokenOutput() {
            return tokenOutput;
        }

        public void setTokenOutput(Long tokenOutput) {
            this.tokenOutput = tokenOutput;
        }
    }
}
