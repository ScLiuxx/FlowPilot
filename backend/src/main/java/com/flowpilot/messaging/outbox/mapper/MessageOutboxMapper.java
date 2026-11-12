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

    /**
     * Moves a row into the publishing state.
     *
     * <p>The update is conditional on the status, so two publishers cannot claim
     * the same row. The moment of the claim is recorded: the recovery of a row
     * whose publisher died is based on it, not on the creation of the row.
     */
    @Update("""
            UPDATE message_outbox
               SET status = 'PUBLISHING',
                   retry_count = retry_count + 1,
                   publishing_at = #{publishingAt}
             WHERE id = #{id}
               AND status = 'PENDING'
            """)
    int markPublishing(@Param("id") Long id, @Param("publishingAt") LocalDateTime publishingAt);

    /**
     * Marks a row as published.
     *
     * <p>The update is conditional on the status: a row that was already
     * recovered by another publisher must not be overwritten.
     */
    @Update("""
            UPDATE message_outbox
               SET status = 'PUBLISHED',
                   published_at = CURRENT_TIMESTAMP(3),
                   publishing_at = NULL
             WHERE id = #{id}
               AND status = 'PUBLISHING'
            """)
    int markPublished(@Param("id") Long id);

    /**
     * Returns a row to the pending state.
     *
     * <p>The update is conditional on the status, so a recovery of another
     * publisher is not undone.
     */
    @Update("""
            UPDATE message_outbox
               SET status = 'PENDING',
                   next_retry_at = #{nextRetryAt},
                   publishing_at = NULL
             WHERE id = #{id}
               AND status = 'PUBLISHING'
            """)
    int reschedule(@Param("id") Long id, @Param("nextRetryAt") LocalDateTime nextRetryAt);

    @Update("UPDATE message_outbox SET status = 'FAILED' WHERE id = #{id}")
    int markFailed(@Param("id") Long id);

    /**
     * Returns rows that were claimed for publishing but never confirmed, so they can be retried
     * after a publisher crash.
     *
     * <p>The age of a claimed row is measured from the moment it was claimed: a row that was
     * created long ago and is published right now is not stale.
     */
    @Select("""
            SELECT * FROM message_outbox
             WHERE status = 'PUBLISHING'
               AND publishing_at IS NOT NULL
               AND publishing_at < #{staleBefore}
             ORDER BY publishing_at
             LIMIT #{limit}
            """)
    List<MessageOutbox> findStalePublishing(@Param("staleBefore") LocalDateTime staleBefore,
                                            @Param("limit") int limit);
}
