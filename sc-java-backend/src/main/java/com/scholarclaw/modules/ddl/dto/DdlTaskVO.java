package com.scholarclaw.modules.ddl.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * DDL 任务视图（{@code 04} §4.6.1 `DdlTaskVO`）。
 *
 * <p>{@code BIGINT} 业务 ID 序列化为十进制字符串；{@code due_at} 使用用户时区偏移量，
 * 其余系统时间戳使用应用时区。{@code reminded_flags} 不直接暴露，转换为三个布尔属性。
 */
public record DdlTaskVO(
        @JsonSerialize(using = ToStringSerializer.class) Long taskId,
        String displayCode,
        @JsonSerialize(using = ToStringSerializer.class) Long courseId,
        String courseName,
        String taskTitle,
        String deliverableFormat,
        String submissionChannel,
        String rawSourceText,
        String rawTimeText,
        String timePrecision,
        boolean isRelativeTime,
        String dueDate,
        String dueTime,
        OffsetDateTime dueAt,
        boolean timeInferred,
        String timeAmbiguityReason,
        String userTimezone,
        BigDecimal confidence,
        String status,
        OffsetDateTime followUpAt,
        int followUpCount,
        boolean remindedT24h,
        boolean remindedT6h,
        boolean remindedOverdue,
        String sourcePlatform,
        OffsetDateTime completedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
