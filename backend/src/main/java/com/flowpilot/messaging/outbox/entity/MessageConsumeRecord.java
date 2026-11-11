package com.flowpilot.messaging.outbox.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Consumer side deduplication record.
 *
 * <p>The unique constraint on {@code (consumer_name, message_id)} is what makes a redelivered
 * message a no-op. The record is inserted in the same transaction as the business handling, so a
 * rolled back business change also rolls back the dedup mark.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("message_consume_record")
public class MessageConsumeRecord {

    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_CONSUMED = "CONSUMED";

    @TableId(type = IdType.INPUT)
    private Long id;

    private String consumerName;
    private String messageId;

    private String status;
    private LocalDateTime consumedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
