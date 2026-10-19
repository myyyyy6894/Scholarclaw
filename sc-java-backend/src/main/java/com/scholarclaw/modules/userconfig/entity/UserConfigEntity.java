package com.scholarclaw.modules.userconfig.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * {@code t_user_config} 实体（{@code 03} §4.1，Single-Writer = `userconfig` 模块）。
 *
 * <p>字段与表列一一对应（下划线转驼峰）。**刻意不包含任何业务方法**：
 * 组 A（认证）与组 B（BYOK/偏好）的更新隔离由 {@code UserConfigService} 的两个显式方法保证，
 * 而不是靠实体上的 setter 自觉（{@code 03} §4.1 专项约束 1）。
 *
 * <p>BYOK 密文字段在这里只是普通 `String`——加解密与掩码属 TASK-203，
 * 本阶段不允许任何代码路径把明文写进这些列。
 */
@TableName("t_user_config")
public class UserConfigEntity {

    /** 组 A：用户 ID（BIGINT AUTO_INCREMENT，`03` §4.0.2）。 */
    @TableId(value = "user_id", type = IdType.AUTO)
    private Long userId;

    /** 组 A：登录用户名（全局唯一）。 */
    private String username;

    /** 组 A：BCrypt 密码哈希（严禁存明文）。 */
    private String passwordHash;

    /** 组 A：账号状态 ACTIVE / DEACTIVATING。 */
    private String accountStatus;

    /** 组 B：对话模型供应商。 */
    private String chatProvider;

    /** 组 B：对话模型 Base URL。 */
    private String chatBaseUrl;

    /** 组 B：对话模型 API Key 密文（TASK-203 起才有值）。 */
    private String chatApiKeyCipher;

    /** 组 B：主对话模型名。 */
    private String chatModel;

    /** 组 B：预留快模型名（v1.1）。 */
    private String fastChatModel;

    /** 组 B：预留推理模型名（v1.1）。 */
    private String slowReasoningModel;

    /** 组 B：默认 Embedding 供应商。 */
    private String defaultEmbeddingProvider;

    /** 组 B：Embedding Base URL。 */
    private String embeddingBaseUrl;

    /** 组 B：Embedding API Key 密文（TASK-203 起才有值）。 */
    private String embeddingApiKeyCipher;

    /** 组 B：默认 Embedding 模型名。 */
    private String defaultEmbeddingModel;

    /** 组 B：默认 Embedding 维度（探测回填）。 */
    private Integer defaultEmbeddingDim;

    /** 组 B：主密钥版本号（MVP 固定 v1）。 */
    private String keyVersion;

    /** 组 B：用户 IANA 时区。 */
    private String timezone;

    /** 组 B：免打扰开始时刻。 */
    private LocalTime dndStartTime;

    /** 组 B：免打扰结束时刻。 */
    private LocalTime dndEndTime;

    /** 组 B：绑定的 IM 平台。 */
    private String boundImPlatform;

    /** 组 B：绑定的 IM 账号标识。 */
    private String boundImUid;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getAccountStatus() {
        return accountStatus;
    }

    public void setAccountStatus(String accountStatus) {
        this.accountStatus = accountStatus;
    }

    public String getChatProvider() {
        return chatProvider;
    }

    public void setChatProvider(String chatProvider) {
        this.chatProvider = chatProvider;
    }

    public String getChatBaseUrl() {
        return chatBaseUrl;
    }

    public void setChatBaseUrl(String chatBaseUrl) {
        this.chatBaseUrl = chatBaseUrl;
    }

    public String getChatApiKeyCipher() {
        return chatApiKeyCipher;
    }

    public void setChatApiKeyCipher(String chatApiKeyCipher) {
        this.chatApiKeyCipher = chatApiKeyCipher;
    }

    public String getChatModel() {
        return chatModel;
    }

    public void setChatModel(String chatModel) {
        this.chatModel = chatModel;
    }

    public String getDefaultEmbeddingProvider() {
        return defaultEmbeddingProvider;
    }

    public void setDefaultEmbeddingProvider(String defaultEmbeddingProvider) {
        this.defaultEmbeddingProvider = defaultEmbeddingProvider;
    }

    public String getEmbeddingBaseUrl() {
        return embeddingBaseUrl;
    }

    public void setEmbeddingBaseUrl(String embeddingBaseUrl) {
        this.embeddingBaseUrl = embeddingBaseUrl;
    }

    public String getEmbeddingApiKeyCipher() {
        return embeddingApiKeyCipher;
    }

    public void setEmbeddingApiKeyCipher(String embeddingApiKeyCipher) {
        this.embeddingApiKeyCipher = embeddingApiKeyCipher;
    }

    public String getDefaultEmbeddingModel() {
        return defaultEmbeddingModel;
    }

    public void setDefaultEmbeddingModel(String defaultEmbeddingModel) {
        this.defaultEmbeddingModel = defaultEmbeddingModel;
    }

    public Integer getDefaultEmbeddingDim() {
        return defaultEmbeddingDim;
    }

    public void setDefaultEmbeddingDim(Integer defaultEmbeddingDim) {
        this.defaultEmbeddingDim = defaultEmbeddingDim;
    }

    public String getKeyVersion() {
        return keyVersion;
    }

    public void setKeyVersion(String keyVersion) {
        this.keyVersion = keyVersion;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public LocalTime getDndStartTime() {
        return dndStartTime;
    }

    public void setDndStartTime(LocalTime dndStartTime) {
        this.dndStartTime = dndStartTime;
    }

    public LocalTime getDndEndTime() {
        return dndEndTime;
    }

    public void setDndEndTime(LocalTime dndEndTime) {
        this.dndEndTime = dndEndTime;
    }

    public String getBoundImPlatform() {
        return boundImPlatform;
    }

    public void setBoundImPlatform(String boundImPlatform) {
        this.boundImPlatform = boundImPlatform;
    }

    public String getBoundImUid() {
        return boundImUid;
    }

    public void setBoundImUid(String boundImUid) {
        this.boundImUid = boundImUid;
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
