package com.scholarclaw.modules.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * {@code t_vector_cleanup_outbox} 实体（{@code 03} §4.10，Single-Writer = `knowledge` 模块）。
 *
 * <p>只承载“ChromaDB 向量尚未确认清理”的持久化补偿意图；不代表业务数据本身。
 */
@TableName("t_vector_cleanup_outbox")
public class VectorCleanupOutboxEntity {

    @TableId(value = "cleanup_id", type = IdType.AUTO)
    private Long cleanupId;

    private String dedupKey;
    private String scope;
    private Long userId;
    private Long courseId;
    private Long docId;
    private Integer courseIndexVersion;
    private Integer staleParseVersion;
    private String status;
    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private String lastError;
    private String traceId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getCleanupId() {
        return cleanupId;
    }

    public void setCleanupId(Long cleanupId) {
        this.cleanupId = cleanupId;
    }

    public String getDedupKey() {
        return dedupKey;
    }

    public void setDedupKey(String dedupKey) {
        this.dedupKey = dedupKey;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
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

    public Long getDocId() {
        return docId;
    }

    public void setDocId(Long docId) {
        this.docId = docId;
    }

    public Integer getCourseIndexVersion() {
        return courseIndexVersion;
    }

    public void setCourseIndexVersion(Integer courseIndexVersion) {
        this.courseIndexVersion = courseIndexVersion;
    }

    public Integer getStaleParseVersion() {
        return staleParseVersion;
    }

    public void setStaleParseVersion(Integer staleParseVersion) {
        this.staleParseVersion = staleParseVersion;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public LocalDateTime getNextRetryAt() {
        return nextRetryAt;
    }

    public void setNextRetryAt(LocalDateTime nextRetryAt) {
        this.nextRetryAt = nextRetryAt;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
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
