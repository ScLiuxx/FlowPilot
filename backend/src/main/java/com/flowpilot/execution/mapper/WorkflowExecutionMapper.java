package com.flowpilot.execution.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowpilot.execution.entity.WorkflowExecution;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface WorkflowExecutionMapper extends BaseMapper<WorkflowExecution> {

    /**
     * Conditional state transition guarded by the optimistic lock version.
     *
     * @return 1 when the transition was applied, 0 when another actor won the race
     */
    @Update("""
            UPDATE workflow_execution
               SET status = #{targetStatus},
                   version = version + 1,
                   started_at = COALESCE(started_at, #{startedAt}),
                   finished_at = #{finishedAt},
                   error_code = #{errorCode},
                   error_message = #{errorMessage},
                   updated_at = CURRENT_TIMESTAMP(3)
             WHERE id = #{id}
               AND version = #{expectedVersion}
               AND status = #{expectedStatus}
            """)
    int compareAndSetStatus(@Param("id") Long id,
                            @Param("expectedStatus") String expectedStatus,
                            @Param("expectedVersion") Long expectedVersion,
                            @Param("targetStatus") String targetStatus,
                            @Param("startedAt") java.time.LocalDateTime startedAt,
                            @Param("finishedAt") java.time.LocalDateTime finishedAt,
                            @Param("errorCode") String errorCode,
                            @Param("errorMessage") String errorMessage);

    /**
     * Allocates the next event sequence number. The row lock serialises concurrent event
     * appends for the same workflow execution.
     */
    @Update("UPDATE workflow_execution SET event_sequence = event_sequence + 1 WHERE id = #{id}")
    int incrementEventSequence(@Param("id") Long id);

    @Select("SELECT event_sequence FROM workflow_execution WHERE id = #{id} FOR UPDATE")
    Long lockEventSequence(@Param("id") Long id);

    /**
     * Locks the execution row for the scheduling decision.
     *
     * <p>The lock is taken <em>before</em> the first plain read of the transaction, so the
     * snapshot of the transaction is created while the lock is held. A second scheduler that
     * waits for the lock therefore sees the nodes the first one dispatched, and the
     * concurrency limit of the workflow is a real limit and not a snapshot of a moment that
     * has already passed.
     */
    @Select("SELECT id FROM workflow_execution WHERE id = #{id} FOR UPDATE")
    Long lockExecution(@Param("id") Long id);

    @Select("""
            SELECT * FROM workflow_execution
             WHERE user_id = #{userId}
               AND idempotency_key = #{idempotencyKey}
             LIMIT 1
            """)
    WorkflowExecution findByIdempotencyKey(@Param("userId") Long userId,
                                            @Param("idempotencyKey") String idempotencyKey);

    @Select("""
            SELECT * FROM workflow_execution
             WHERE user_id = #{userId}
             ORDER BY created_at DESC
             LIMIT #{limit}
            """)
    List<WorkflowExecution> findRecentByUser(@Param("userId") Long userId, @Param("limit") int limit);

    /**
     * The number of the executions of one user.
     *
     * <p>The owner is {@code null} for the administrator, who sees the executions of everybody.
     *
     * @param userId the owner of the executions, {@code null} counts all of them
     * @param status the status to count, {@code null} counts every status
     */
    @Select("""
            <script>
            SELECT COUNT(*) FROM workflow_execution
             WHERE 1 = 1
               <if test="userId != null">
                 AND user_id = #{userId}
               </if>
               <if test="status != null and status != ''">
                 AND status = #{status}
               </if>
            </script>
            """)
    long countByOwner(@Param("userId") Long userId, @Param("status") String status);

    /**
     * Recomputes the node counters of an execution.
     *
     * <p>The counters are the progress of the workflow. They are refreshed while the workflow
     * runs, so the console shows how far it got and not only its final state.
     *
     * <p>The counters are derived from the states of the nodes, so they cannot drift away from
     * the nodes they describe. The version is not touched: the counters carry no state of their
     * own, and a state transition must not be rejected because of them.
     */
    @Update("""
            UPDATE workflow_execution e
               SET completed_node_count = (SELECT COUNT(*)
                                             FROM node_execution n
                                            WHERE n.workflow_execution_id = e.id
                                              AND n.status IN ('SUCCEEDED', 'SKIPPED')),
                   failed_node_count = (SELECT COUNT(*)
                                          FROM node_execution n
                                         WHERE n.workflow_execution_id = e.id
                                           AND n.status = 'FAILED')
             WHERE e.id = #{executionId}
            """)
    int refreshNodeCounts(@Param("executionId") Long executionId);
}
