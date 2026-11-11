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
}
