package com.flowpilot.messaging.outbox.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowpilot.messaging.outbox.entity.MessageOutbox;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface MessageOutboxMapper extends BaseMapper<MessageOutbox> {

    /**
     * Claims a batch of pending messages. {@code FOR UPDATE SKIP LOCKED} lets several publisher
     * instances work in parallel without claiming the same row twice.
     */
    @Select("""
            SELECT * FROM message_outbox
             WHERE status = 'PENDING'
               AND (next_retry_at IS NULL OR next_retry_at <= #{now})
             ORDER BY created_at
             LIMIT #{limit}
             FOR UPDATE SKIP LOCKED
            """)
    List<MessageOutbox> claimPending(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Update("""
            UPDATE message_outbox
               SET status = 'PUBLISHING',
                   retry_count = retry_count + 1
             WHERE id = #{id}
               AND status = 'PENDING'
            """)
    int markPublishing(@Param("id") Long id);

    @Update("""
            UPDATE message_outbox
               SET status = 'PUBLISHED',
                   published_at = CURRENT_TIMESTAMP(3)
             WHERE id = #{id}
            """)
    int markPublished(@Param("id") Long id);

    @Update("""
            UPDATE message_outbox
               SET status = 'PENDING',
                   next_retry_at = #{nextRetryAt}
             WHERE id = #{id}
            """)
    int reschedule(@Param("id") Long id, @Param("nextRetryAt") LocalDateTime nextRetryAt);

    @Update("UPDATE message_outbox SET status = 'FAILED' WHERE id = #{id}")
    int markFailed(@Param("id") Long id);

    /**
     * Returns rows that were claimed for publishing but never confirmed, so they can be retried
     * after a publisher crash.
     */
    @Select("""
            SELECT * FROM message_outbox
             WHERE status = 'PUBLISHING'
               AND created_at < #{staleBefore}
             ORDER BY created_at
             LIMIT #{limit}
            """)
    List<MessageOutbox> findStalePublishing(@Param("staleBefore") LocalDateTime staleBefore,
                                            @Param("limit") int limit);
}
