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
 * A message that must be delivered to RabbitMQ.
 *
 * <p>The row is written inside the same MySQL transaction as the business state change, which
 * removes the dual write problem between the database and the broker. A publisher then scans the
 * table and forwards the rows.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("message_outbox")
public class MessageOutbox {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PUBLISHING = "PUBLISHING";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_FAILED = "FAILED";

    @TableId(type = IdType.INPUT)
    private Long id;

    private String messageId;
    private String messageType;

    private String aggregateType;
    private Long aggregateId;

    private String routingKey;
    private String payload;

    private String status;

    private Integer retryCount;
    private LocalDateTime nextRetryAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    private LocalDateTime publishedAt;
}
