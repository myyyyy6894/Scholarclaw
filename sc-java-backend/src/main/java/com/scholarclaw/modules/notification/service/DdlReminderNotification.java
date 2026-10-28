package com.scholarclaw.modules.notification.service;

import java.time.LocalDateTime;

/**
 * DDL 提醒的通知载荷（TASK-802 消费 TASK-801 的 {@code DdlReminderDispatch}）。
 *
 * <p>{@code dueAtUserZone} 是按 {@code userTimezone} 换算后的展示时间；{@code null}
 * 表示任务没有正式截止时间（{@code PENDING_TIME}）。notification 模块不依赖 ddl 模块，
 * 由调用方把提醒字段映射成该记录。
 */
public record DdlReminderNotification(
        Long userId,
        Long taskId,
        NotificationBizType bizType,
        String courseName,
        String taskTitle,
        LocalDateTime dueAtUserZone,
        String userTimezone,
        boolean timeInferred,
        int followUpCount,
        String traceId) {
}
