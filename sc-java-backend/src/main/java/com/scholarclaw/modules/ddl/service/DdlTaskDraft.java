package com.scholarclaw.modules.ddl.service;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.scholarclaw.common.config.ScTimeZone;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.ddl.dto.TaskChangeDraftVO;
import com.scholarclaw.modules.ddl.entity.DdlTaskEntity;
import java.math.BigDecimal;
import java.time.DateTimeException;
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
 *
 * <p>{@code schemaVersion} 用于淘汰旧版本草案：缺少该字段的旧 JSON 反序列化为 `0`，
 * {@link DdlDraftStore} 会立即删除并跳过，避免缺少 {@code target_task_updated_at} 的旧草案
 * 在版本校验中被误用。
 */
public record DdlTaskDraft(
        @JsonSerialize(using = ToStringSerializer.class) Long targetTaskId,
        /** 草案生成时目标任务的 updated_at 快照；决策前必须校验，目标被编辑/完成后拒绝旧草案。 */
        String targetTaskUpdatedAt,
        @JsonSerialize(using = ToStringSerializer.class) Long courseId,
        String courseName,
        String taskTitle,
        String timePrecision,
        String dueDate,
        String dueTime,
        String dueAt,
        boolean timeInferred,
        String timeAmbiguityReason,
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
        String expiresAt,
        /** 草案 JSON 版本；缺失（旧草案）时为 0，必须删除并跳过。 */
        int schemaVersion) {

    public static final int CURRENT_VERSION = 2;

    private static final DateTimeFormatter DUE_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** 兼容旧测试/旧调用方的 22 参构造：自动写入当前 schema 版本。 */
    public DdlTaskDraft(Long targetTaskId, String targetTaskUpdatedAt, Long courseId,
                        String courseName, String taskTitle, String timePrecision,
                        String dueDate, String dueTime, String dueAt, boolean timeInferred,
                        String timeAmbiguityReason, String status, String followUpAt,
                        String deliverableFormat, String submissionChannel,
                        String rawSourceText, String rawTimeText, String userTimezone,
                        BigDecimal confidence, boolean isRelativeTime, String contentHash,
                        String expiresAt) {
        this(targetTaskId, targetTaskUpdatedAt, courseId, courseName, taskTitle, timePrecision,
                dueDate, dueTime, dueAt, timeInferred, timeAmbiguityReason, status, followUpAt,
                deliverableFormat, submissionChannel, rawSourceText, rawTimeText, userTimezone,
                confidence, isRelativeTime, contentHash, expiresAt, CURRENT_VERSION);
    }

    public static DdlTaskDraft fromEntity(DdlTaskEntity entity, Long targetTaskId, String expiresAt) {
        return fromEntity(entity, targetTaskId, null, expiresAt);
    }

    /**
     * @param targetTaskUpdatedAt 目标任务在生成草案时的 {@code updated_at} 快照；
     *                            生产路径必须传入，否则决策时按“旧草案”拒绝。
     */
    public static DdlTaskDraft fromEntity(DdlTaskEntity entity, Long targetTaskId,
                                          java.time.LocalDateTime targetTaskUpdatedAt,
                                          String expiresAt) {
        return new DdlTaskDraft(
                targetTaskId,
                targetTaskUpdatedAt == null ? null
                        : targetTaskUpdatedAt.truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
                                .toString(),
                entity.getCourseId(),
                entity.getCourseName(),
                entity.getTaskTitle(),
                entity.getTimePrecision(),
                entity.getDueDate() == null ? null : entity.getDueDate().toString(),
                entity.getDueTime() == null ? null : entity.getDueTime().format(DUE_TIME_FORMAT),
                entity.getDueAt() == null ? null : entity.getDueAt().toString(),
                entity.getTimeInferred() != null && entity.getTimeInferred() == 1,
                entity.getTimeAmbiguityReason(),
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
                expiresAt,
                CURRENT_VERSION);
    }

    public boolean isCurrentSchema() {
        return schemaVersion == CURRENT_VERSION;
    }

    /** 版本快照必须在生成草案时写入；缺失视为旧草案。 */
    public boolean hasTargetVersion() {
        return targetTaskUpdatedAt != null && !targetTaskUpdatedAt.isBlank();
    }

    public boolean isExpired(LocalDateTime now) {
        LocalDateTime expires = expiresAtValue();
        return expires == null || !expires.isAfter(now);
    }

    /**
     * 供看板/响应展示的草案视图。
     *
     * <p>新截止时间与有效期一律按**草案携带的用户时区快照**换算；缺失或非法时回退产品默认
     * `Asia/Shanghai`，绝不使用调用方（可能是浏览器）时区。
     */
    public TaskChangeDraftVO toVO() {
        ZoneId draftZone = draftZone();
        return new TaskChangeDraftVO(
                targetTaskId,
                taskTitle,
                timePrecision,
                dueDate,
                dueTime,
                toUserOffset(dueAt, draftZone),
                timeInferred,
                timeAmbiguityReason,
                deliverableFormat,
                submissionChannel,
                rawSourceText,
                rawTimeText,
                draftZone.getId(),
                expiresAt == null ? null : toUserOffset(expiresAt, draftZone));
    }

    /** 保留旧签名，但忽略调用方时区：草案必须按自身快照展示。 */
    public TaskChangeDraftVO toVO(ZoneId ignoredCallerZone) {
        return toVO();
    }

    public ZoneId draftZone() {
        String zone = userTimezone == null ? null : userTimezone.trim();
        if (zone == null || zone.isEmpty()) {
            return ScTimeZone.zoneId();
        }
        try {
            return ZoneId.of(zone);
        } catch (DateTimeException e) {
            return ScTimeZone.zoneId();
        }
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

    public LocalDateTime targetTaskUpdatedAtValue() {
        return targetTaskUpdatedAt == null ? null : parseDateTime(targetTaskUpdatedAt);
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
