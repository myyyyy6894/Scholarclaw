package com.scholarclaw.modules.ddl.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * {@code t_ddl_decision_receipt} 实体（TASK-704 审阅修复）。
 *
 * <p>主键 {@code receipt_id} 只用于自增；业务唯一键是 {@code (user_id, client_request_id)}。
 * 决策回执必须持久化到 MySQL，Redis 只作为性能缓存：否则 Redis 过期或故障后，
 * 同一 {@code client_request_id} 的重试可能第二次执行变更。
 */
@TableName("t_ddl_decision_receipt")
public class DdlDecisionReceiptEntity {

    @TableId(value = "receipt_id", type = IdType.AUTO)
    private Long receiptId;

    private Long userId;
    private String clientRequestId;
    private String requestFingerprint;
    private String decision;
    private Long targetTaskId;
    private Long resultTaskId;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;

    public Long getReceiptId() {
        return receiptId;
    }

    public void setReceiptId(Long receiptId) {
        this.receiptId = receiptId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getClientRequestId() {
        return clientRequestId;
    }

    public void setClientRequestId(String clientRequestId) {
        this.clientRequestId = clientRequestId;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public void setRequestFingerprint(String requestFingerprint) {
        this.requestFingerprint = requestFingerprint;
    }

    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }

    public Long getTargetTaskId() {
        return targetTaskId;
    }

    public void setTargetTaskId(Long targetTaskId) {
        this.targetTaskId = targetTaskId;
    }

    public Long getResultTaskId() {
        return resultTaskId;
    }

    public void setResultTaskId(Long resultTaskId) {
        this.resultTaskId = resultTaskId;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
