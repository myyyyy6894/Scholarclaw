package com.scholarclaw.modules.ddl.service;

import com.scholarclaw.common.config.ScTimeZone;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.ddl.dto.TaskChangeDraftVO;
import com.scholarclaw.modules.ddl.entity.DdlTaskEntity;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Redis 中的重复通知待确认草案（`sc:u:{uid}:task_draft:{task_id}`，TTL 24h）。
 *
 * <p>草案**不是业务权威数据**：MySQL 中的原任务才是权威。草案只保存“若用户确认更新/仍然新建”
 * 所需的新字段；过期或损坏时用户重新提取即可。
 */
public record DdlTaskDraft(
        Long targetTaskId,
        Long courseId,
        String courseName,
        String taskTitle,
        String timePrecision,
        String dueDate,
        String dueTime,
        String dueAt,
        boolean timeInferred,
        String status,
        String followUpAt,
        String deliverableFormat,
        String submissionChannel,
        String rawSourceText,
        String rawTimeText,
        String userTimezone,
        BigDecimal confidence,
        boolean isRelativeTime,
        String contentHash,
        String expiresAt) {

    private static final DateTimeFormatter DUE_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    public static DdlTaskDraft fromEntity(DdlTaskEntity entity, Long targetTaskId, String expiresAt) {
        return new DdlTaskDraft(
                targetTaskId,
                entity.getCourseId(),
                entity.getCourseName(),
                entity.getTaskTitle(),
                entity.getTimePrecision(),
                entity.getDueDate() == null ? null : entity.getDueDate().toString(),
                entity.getDueTime() == null ? null : entity.getDueTime().format(DUE_TIME_FORMAT),
                entity.getDueAt() == null ? null : entity.getDueAt().toString(),
                entity.getTimeInferred() != null && entity.getTimeInferred() == 1,
                entity.getStatus(),
                entity.getFollowUpAt() == null ? null : entity.getFollowUpAt().toString(),
                entity.getDeliverableFormat(),
                entity.getSubmissionChannel(),
                entity.getRawSourceText(),
                entity.getRawTimeText(),
                entity.getUserTimezone(),
                entity.getConfidence(),
                entity.getIsRelativeTime() != null && entity.getIsRelativeTime() == 1,
                entity.getContentHash(),
                expiresAt);
    }

    public TaskChangeDraftVO toVO(ZoneId userZone) {
        return new TaskChangeDraftVO(
                targetTaskId,
                taskTitle,
                timePrecision,
                dueDate,
                dueTime,
                toUserOffset(dueAt, userZone),
                timeInferred,
                deliverableFormat,
                submissionChannel,
                rawSourceText,
                rawTimeText,
                expiresAt == null ? null : ScTimeZone.toOffsetDateTime(expiresAtValue()));
    }

    public LocalDate dueDateValue() {
        return dueDate == null ? null : parseDate(dueDate);
    }

    public LocalTime dueTimeValue() {
        return dueTime == null ? null : parseTime(dueTime);
    }

    public LocalDateTime dueAtValue() {
        return dueAt == null ? null : parseDateTime(dueAt);
    }

    public LocalDateTime followUpAtValue() {
        return followUpAt == null ? null : parseDateTime(followUpAt);
    }

    public LocalDateTime expiresAtValue() {
        return expiresAt == null ? null : parseDateTime(expiresAt);
    }

    private static OffsetDateTime toUserOffset(String appLocal, ZoneId userZone) {
        if (appLocal == null) {
            return null;
        }
        return ScTimeZone.toOffsetDateTime(parseDateTime(appLocal))
                .atZoneSameInstant(userZone)
                .toOffsetDateTime();
    }

    private static LocalDate parseDate(String raw) {
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            throw invalidDraft();
        }
    }

    private static LocalTime parseTime(String raw) {
        try {
            return LocalTime.parse(raw, DUE_TIME_FORMAT);
        } catch (DateTimeParseException e) {
            throw invalidDraft();
        }
    }

    private static LocalDateTime parseDateTime(String raw) {
        try {
            return LocalDateTime.parse(raw);
        } catch (DateTimeParseException e) {
            throw invalidDraft();
        }
    }

    private static BusinessException invalidDraft() {
        return new BusinessException(ErrorCode.DRAFT_EXPIRED_OR_NOT_FOUND,
                "变更草案已失效，请重新提取通知。");
    }
}
