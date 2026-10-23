package com.scholarclaw.modules.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** {@code t_chat_message} 实体（{@code 03} §4.6，Single-Writer = `agent`）。 */
@TableName("t_chat_message")
public class ChatMessageEntity {

    @TableId(value = "message_id", type = IdType.AUTO)
    private Long messageId;
    private Long userId;
    private Long courseId;
    private String platform;
    private String platformMsgId;
    private String role;
    private String intentType;
    private String content;
    private String citationsJson;
    private Boolean isRejected;
    private String traceId;
    private Integer retrievalCostMs;
    private Integer llmFirstTokenMs;
    private LocalDateTime createdAt;

    public Long getMessageId() {
        return messageId;
    }

    public void setMessageId(Long messageId) {
        this.messageId = messageId;
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

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public String getPlatformMsgId() {
        return platformMsgId;
    }

    public void setPlatformMsgId(String platformMsgId) {
        this.platformMsgId = platformMsgId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getIntentType() {
        return intentType;
    }

    public void setIntentType(String intentType) {
        this.intentType = intentType;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getCitationsJson() {
        return citationsJson;
    }

    public void setCitationsJson(String citationsJson) {
        this.citationsJson = citationsJson;
    }

    public Boolean getIsRejected() {
        return isRejected;
    }

    public void setIsRejected(Boolean isRejected) {
        this.isRejected = isRejected;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public Integer getRetrievalCostMs() {
        return retrievalCostMs;
    }

    public void setRetrievalCostMs(Integer retrievalCostMs) {
        this.retrievalCostMs = retrievalCostMs;
    }

    public Integer getLlmFirstTokenMs() {
        return llmFirstTokenMs;
    }

    public void setLlmFirstTokenMs(Integer llmFirstTokenMs) {
        this.llmFirstTokenMs = llmFirstTokenMs;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
