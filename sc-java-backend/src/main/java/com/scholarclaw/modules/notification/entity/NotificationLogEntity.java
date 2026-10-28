package com.scholarclaw.modules.notification.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * {@code t_notification_log} 实体（{@code 03} §4.7，Single-Writer = `notification` 模块）。
 *
 * <p>{@code outbound_status} 没有数据库默认值，必须由 {@code NotificationService} 显式判定；
 * 站内信记录先落库，外部 IM 推送状态只描述投递进度，绝不影响 Web 侧可见性。
 */
@TableName("t_notification_log")
public class NotificationLogEntity {

    @TableId(value = "notification_id", type = IdType.AUTO)
    private Long notificationId;

    private Long userId;
    private String bizType;
    private Long bizId;
    private String title;
    private String content;
    private String readStatus;
    private String outboundStatus;
    private String targetImPlatform;
    private String targetImUid;
    private Integer imRetryCount;
    private String imErrorMsg;
    private LocalDateTime scheduledPushAt;
    private LocalDateTime sentImAt;
    private LocalDateTime readAt;
    private String traceId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getNotificationId() {
        return notificationId;
    }

    public void setNotificationId(Long notificationId) {
        this.notificationId = notificationId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getBizType() {
        return bizType;
    }

    public void setBizType(String bizType) {
        this.bizType = bizType;
    }

    public Long getBizId() {
        return bizId;
    }

    public void setBizId(Long bizId) {
        this.bizId = bizId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getReadStatus() {
        return readStatus;
    }

    public void setReadStatus(String readStatus) {
        this.readStatus = readStatus;
    }

    public String getOutboundStatus() {
        return outboundStatus;
    }

    public void setOutboundStatus(String outboundStatus) {
        this.outboundStatus = outboundStatus;
    }

    public String getTargetImPlatform() {
        return targetImPlatform;
    }

    public void setTargetImPlatform(String targetImPlatform) {
        this.targetImPlatform = targetImPlatform;
    }

    public String getTargetImUid() {
        return targetImUid;
    }

    public void setTargetImUid(String targetImUid) {
        this.targetImUid = targetImUid;
    }

    public Integer getImRetryCount() {
        return imRetryCount;
    }

    public void setImRetryCount(Integer imRetryCount) {
        this.imRetryCount = imRetryCount;
    }

    public String getImErrorMsg() {
        return imErrorMsg;
    }

    public void setImErrorMsg(String imErrorMsg) {
        this.imErrorMsg = imErrorMsg;
    }

    public LocalDateTime getScheduledPushAt() {
        return scheduledPushAt;
    }

    public void setScheduledPushAt(LocalDateTime scheduledPushAt) {
        this.scheduledPushAt = scheduledPushAt;
    }

    public LocalDateTime getSentImAt() {
        return sentImAt;
    }

    public void setSentImAt(LocalDateTime sentImAt) {
        this.sentImAt = sentImAt;
    }

    public LocalDateTime getReadAt() {
        return readAt;
    }

    public void setReadAt(LocalDateTime readAt) {
        this.readAt = readAt;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
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
