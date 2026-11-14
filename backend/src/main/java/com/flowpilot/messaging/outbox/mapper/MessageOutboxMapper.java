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
     *
     * <p>Only the identifiers are selected: the rows are sorted by the moment of their creation,
     * and the sort would carry the whole payload of every message through the sort buffer of the
     * database. A message with a payload of a few hundred kilobytes does not fit into it and the
     * publisher fails with "out of sort memory". The payloads are read afterwards by their
     * identifiers, which is a lookup by the primary key.
     */
    @Select("""
            SELECT id FROM message_outbox
             WHERE status = 'PENDING'
               AND (next_retry_at IS NULL OR next_retry_at <= #{now})
             ORDER BY created_at
             LIMIT #{limit}
             FOR UPDATE SKIP LOCKED
            """)
    List<Long> claimPendingIds(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /**
     * Reads the rows of the claimed messages by their identifiers.
     */
    @Select("""
            <script>
            SELECT * FROM message_outbox WHERE id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<MessageOutbox> selectByIds(@Param("ids") List<Long> ids);

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

    /**
     * Gives up on a row that cannot be published.
     *
     * <p>The update is conditional on the status, so a row that was recovered by another
     * publisher in the meantime is not overwritten.
     */
    @Update("""
            UPDATE message_outbox
               SET status = 'FAILED',
                   next_retry_at = NULL
             WHERE id = #{id}
               AND status = 'PUBLISHING'
            """)
    int markFailed(@Param("id") Long id);

    /**
     * Returns rows that were claimed for publishing but never confirmed, so they can be retried
     * after a publisher crash.
     *
     * <p>The age of a claimed row is measured from the moment it was claimed: a row that was
     * created long ago and is published right now is not stale.
     *
     * <p>Only the identifiers are selected, for the same reason as in {@link #claimPendingIds}.
     */
    @Select("""
            SELECT id FROM message_outbox
             WHERE status = 'PUBLISHING'
               AND publishing_at IS NOT NULL
               AND publishing_at < #{staleBefore}
             ORDER BY publishing_at
             LIMIT #{limit}
            """)
    List<Long> findStalePublishingIds(@Param("staleBefore") LocalDateTime staleBefore,
                                       @Param("limit") int limit);
}
