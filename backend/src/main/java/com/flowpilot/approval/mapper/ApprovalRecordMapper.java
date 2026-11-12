package com.flowpilot.approval.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowpilot.approval.entity.ApprovalRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ApprovalRecordMapper extends BaseMapper<ApprovalRecord> {

    @Select("""
            SELECT * FROM approval_record
             WHERE workflow_execution_id = #{executionId}
               AND status = 'PENDING'
             ORDER BY id DESC
            """)
    ApprovalRecord findPendingByExecution(@Param("executionId") Long executionId);

    /**
     * Conditional decision update. Only the first decision wins, which makes the approval
     * idempotent even if the user double clicks the approve button.
     */
    @Update("""
            UPDATE approval_record
               SET status = #{status},
                   decision_data = #{decisionData},
                   comment = #{comment},
                   approver_id = #{approverId},
                   decided_at = CURRENT_TIMESTAMP(3)
             WHERE id = #{id}
               AND status = 'PENDING'
            """)
    int decide(@Param("id") Long id,
               @Param("status") String status,
               @Param("decisionData") String decisionData,
               @Param("comment") String comment,
               @Param("approverId") Long approverId);

    @Select("""
            SELECT * FROM approval_record
             WHERE status = 'PENDING'
             ORDER BY created_at DESC
             LIMIT #{limit}
            """)
    java.util.List<ApprovalRecord> findPending(@Param("limit") int limit);

    /**
     * The approvals of the executions of one user.
     *
     * <p>An approval belongs to the execution it belongs to, and an execution belongs to the user
     * that started it. Filtering only on the approval table would expose the approvals of every
     * user, therefore the list has to join the execution table.
     *
     * <p>The join is always there and the user filter is a condition on it: an administrator
     * passes no user id and therefore sees the approvals of everybody.
     */
    @Select("""
            <script>
            SELECT a.* FROM approval_record a
              JOIN workflow_execution e ON e.id = a.workflow_execution_id
             WHERE 1 = 1
               <if test="userId != null">
                 AND e.user_id = #{userId}
               </if>
               <if test="status != null and status != ''">
                 AND a.status = #{status}
               </if>
             ORDER BY a.created_at DESC
             LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    java.util.List<ApprovalRecord> findByOwner(@Param("userId") Long userId,
                                               @Param("status") String status,
                                               @Param("limit") int limit,
                                               @Param("offset") int offset);

    /**
     * The number of the approvals of the executions of one user.
     */
    @Select("""
            <script>
            SELECT COUNT(*) FROM approval_record a
              JOIN workflow_execution e ON e.id = a.workflow_execution_id
             WHERE 1 = 1
               <if test="userId != null">
                 AND e.user_id = #{userId}
               </if>
               <if test="status != null and status != ''">
                 AND a.status = #{status}
               </if>
            </script>
            """)
    long countByOwner(@Param("userId") Long userId, @Param("status") String status);

    /**
     * The approvals whose deadline elapsed.
     *
     * <p>An approval without a deadline never expires, so the condition on the deadline is
     * explicit.
     */
    @Select("""
            SELECT * FROM approval_record
             WHERE status = 'PENDING'
               AND expire_at IS NOT NULL
               AND expire_at <= #{now}
             ORDER BY expire_at
             LIMIT #{limit}
            """)
    java.util.List<ApprovalRecord> findExpired(@Param("now") java.time.LocalDateTime now,
                                               @Param("limit") int limit);

    /**
     * Conditional update of an expired approval. Only the first scanner wins, so an approval is
     * never expired twice.
     */
    @Update("""
            UPDATE approval_record
               SET status = 'EXPIRED',
                   decision_data = #{decisionData},
                   comment = #{comment},
                   decided_at = CURRENT_TIMESTAMP(3)
             WHERE id = #{id}
               AND status = 'PENDING'
            """)
    int expire(@Param("id") Long id,
               @Param("decisionData") String decisionData,
               @Param("comment") String comment);
}
