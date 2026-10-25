package com.scholarclaw.modules.ddl.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * {@code t_ddl_task} 实体（{@code 03} §4.5，Single-Writer = `ddl` 模块）。
 *
 * <p>字段与表列一一对应（下划线转驼峰）。{@code due_date}/{@code due_time} 保存用户时区下的
 * **墙钟**日期与时刻；{@code due_at} 保存换算到应用时区（{@link com.scholarclaw.common.config.ScTimeZone}）
 * 的绝对时间，确保与其他 DATETIME 列可比。读取时再按 {@code user_timezone} 快照换回用户时区展示。
 *
 * <p>控制字段 {@code idempotency_key}、{@code content_hash} 与整型 {@code reminded_flags}
 * 不直接暴露给前端，由 Service 转换为可读属性。
 */
@TableName("t_ddl_task")
public class DdlTaskEntity {

    @TableId(value = "task_id", type = IdType.AUTO)
    private Long taskId;

    private Long userId;
    private Long courseId;
    private String courseName;
    private String taskTitle;
    private String deliverableFormat;
    private String submissionChannel;
    private String rawSourceText;
    private String rawTimeText;
    private String timePrecision;
    private Integer isRelativeTime;
    private LocalDate dueDate;
    private LocalTime dueTime;
    private LocalDateTime dueAt;
    private Integer timeInferred;
    private String userTimezone;
    private BigDecimal confidence;
    private String status;
    private LocalDateTime followUpAt;
    private Integer followUpCount;
    private Integer remindedFlags;
    private Integer hasSubtasks;
    private String sourcePlatform;
    private String sourceMsgId;
    private String idempotencyKey;
    private String contentHash;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getCourseId() {
        return courseId;
    }

    public void setCourseId(Long courseId) {
        this.courseId = courseId;
    }

    public String getCourseName() {
        return courseName;
    }

    public void setCourseName(String courseName) {
        this.courseName = courseName;
    }

    public String getTaskTitle() {
        return taskTitle;
    }

    public void setTaskTitle(String taskTitle) {
        this.taskTitle = taskTitle;
    }

    public String getDeliverableFormat() {
        return deliverableFormat;
    }

    public void setDeliverableFormat(String deliverableFormat) {
        this.deliverableFormat = deliverableFormat;
    }

    public String getSubmissionChannel() {
        return submissionChannel;
    }

    public void setSubmissionChannel(String submissionChannel) {
        this.submissionChannel = submissionChannel;
    }

    public String getRawSourceText() {
        return rawSourceText;
    }

    public void setRawSourceText(String rawSourceText) {
        this.rawSourceText = rawSourceText;
    }

    public String getRawTimeText() {
        return rawTimeText;
    }

    public void setRawTimeText(String rawTimeText) {
        this.rawTimeText = rawTimeText;
    }

    public String getTimePrecision() {
        return timePrecision;
    }

    public void setTimePrecision(String timePrecision) {
        this.timePrecision = timePrecision;
    }

    public Integer getIsRelativeTime() {
        return isRelativeTime;
    }

    public void setIsRelativeTime(Integer isRelativeTime) {
        this.isRelativeTime = isRelativeTime;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public void setDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }

    public LocalTime getDueTime() {
        return dueTime;
    }

    public void setDueTime(LocalTime dueTime) {
        this.dueTime = dueTime;
    }

    public LocalDateTime getDueAt() {
        return dueAt;
    }

    public void setDueAt(LocalDateTime dueAt) {
        this.dueAt = dueAt;
    }

    public Integer getTimeInferred() {
        return timeInferred;
    }

    public void setTimeInferred(Integer timeInferred) {
        this.timeInferred = timeInferred;
    }

    public String getUserTimezone() {
        return userTimezone;
    }

    public void setUserTimezone(String userTimezone) {
        this.userTimezone = userTimezone;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public void setConfidence(BigDecimal confidence) {
        this.confidence = confidence;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getFollowUpAt() {
        return followUpAt;
    }

    public void setFollowUpAt(LocalDateTime followUpAt) {
        this.followUpAt = followUpAt;
    }

    public Integer getFollowUpCount() {
        return followUpCount;
    }

    public void setFollowUpCount(Integer followUpCount) {
        this.followUpCount = followUpCount;
    }

    public Integer getRemindedFlags() {
        return remindedFlags;
    }

    public void setRemindedFlags(Integer remindedFlags) {
        this.remindedFlags = remindedFlags;
    }

    public Integer getHasSubtasks() {
        return hasSubtasks;
    }

    public void setHasSubtasks(Integer hasSubtasks) {
        this.hasSubtasks = hasSubtasks;
    }

    public String getSourcePlatform() {
        return sourcePlatform;
    }

    public void setSourcePlatform(String sourcePlatform) {
        this.sourcePlatform = sourcePlatform;
    }

    public String getSourceMsgId() {
        return sourceMsgId;
    }

    public void setSourceMsgId(String sourceMsgId) {
        this.sourceMsgId = sourceMsgId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
