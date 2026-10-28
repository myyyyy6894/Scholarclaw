package com.scholarclaw.modules.ddl.service;

import java.time.LocalDateTime;

/**
 * 一次已成功领取的 DDL 提醒（TASK-801）。
 *
 * <p><b>边界：</b>本记录只代表 {@code t_ddl_task} 的提醒位 / 跟进计数已被原子更新，
 * 表示“该梯度归本次调度所有”。站内通知落库、未读计数与外部 IM 推送分别属
 * TASK-802 / TASK-804；TASK-802 必须在 {@link DdlReminderClaimer#claim} 的同一本地事务内
 * 消费该记录，避免出现“已标记提醒但通知丢失”的中间态。
 *
 * <p>{@code dueAt} 是应用时区（{@link com.scholarclaw.common.config.ScTimeZone}）墙钟时间；
 * {@code dueAtUserZone} 是按 {@code userTimezone} 换算后的展示时间，二者可为 {@code null}
 * （例如 {@code PENDING_TIME} 没有截止时间）。
 */
public record DdlReminderDispatch(
        Long userId,
        Long taskId,
        Long courseId,
        DdlReminderType type,
        String courseName,
        String taskTitle,
        String deliverableFormat,
        String submissionChannel,
        String rawTimeText,
        String timePrecision,
        boolean timeInferred,
        LocalDateTime dueAt,
        LocalDateTime dueAtUserZone,
        String userTimezone,
        int followUpCount,
        LocalDateTime occurredAt,
        String traceId) {
}
