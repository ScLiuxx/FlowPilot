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

    /**
     * The reports of the executions of one user.
     *
     * <p>A report belongs to the user of its execution, so the list has to join the execution
     * table. Filtering only on the report table would expose the reports of every user.
     *
     * <p>The join is always there and the user filter is a condition on it: an administrator
     * passes no user id and therefore sees the reports of everybody.
     *
     * @param userId the owner of the executions, {@code null} returns the reports of everybody
     */
    @Select("""
            <script>
            SELECT r.* FROM analysis_report r
              JOIN workflow_execution e ON e.id = r.workflow_execution_id
             WHERE 1 = 1
               <if test="userId != null">
                 AND e.user_id = #{userId}
               </if>
               <if test="status != null and status != ''">
                 AND r.status = #{status}
               </if>
             ORDER BY r.created_at DESC
             LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<AnalysisReport> findByOwner(@Param("userId") Long userId,
                                     @Param("status") String status,
                                     @Param("limit") int limit,
                                     @Param("offset") int offset);

    /**
     * The number of the reports of the executions of one user.
     */
    @Select("""
            <script>
            SELECT COUNT(*) FROM analysis_report r
              JOIN workflow_execution e ON e.id = r.workflow_execution_id
             WHERE 1 = 1
               <if test="userId != null">
                 AND e.user_id = #{userId}
               </if>
               <if test="status != null and status != ''">
                 AND r.status = #{status}
               </if>
            </script>
            """)
    long countByOwner(@Param("userId") Long userId, @Param("status") String status);
}
