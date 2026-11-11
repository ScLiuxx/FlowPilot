package com.flowpilot.messaging.outbox.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowpilot.messaging.outbox.entity.MessageConsumeRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface MessageConsumeRecordMapper extends BaseMapper<MessageConsumeRecord> {

    @Select("""
            SELECT * FROM message_consume_record
             WHERE consumer_name = #{consumerName}
               AND message_id = #{messageId}
            """)
    MessageConsumeRecord find(@Param("consumerName") String consumerName,
                              @Param("messageId") String messageId);

    @Update("""
            UPDATE message_consume_record
               SET status = 'CONSUMED',
                   consumed_at = CURRENT_TIMESTAMP(3)
             WHERE consumer_name = #{consumerName}
               AND message_id = #{messageId}
            """)
    int markConsumed(@Param("consumerName") String consumerName, @Param("messageId") String messageId);
}
