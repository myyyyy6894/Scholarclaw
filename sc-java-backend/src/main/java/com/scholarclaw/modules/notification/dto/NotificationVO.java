package com.scholarclaw.modules.notification.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.time.OffsetDateTime;

/**
 * 站内通知视图（{@code 04} §4.7.1 `NotificationVO`）。
 *
 * <p>{@code notification_id} / {@code biz_id} 为 BIGINT，统一序列化为十进制字符串；
 * 时间为带应用时区偏移量的 ISO-8601。{@code content} 原样返回，前端按纯文本渲染。
 */
public record NotificationVO(
        @JsonSerialize(using = ToStringSerializer.class) Long notificationId,
        String bizType,
        @JsonSerialize(using = ToStringSerializer.class) Long bizId,
        String title,
        String content,
        String readStatus,
        String outboundStatus,
        OffsetDateTime sentImAt,
        OffsetDateTime readAt,
        OffsetDateTime createdAt) {
}
