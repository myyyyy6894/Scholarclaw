package com.scholarclaw.modules.userconfig.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

/**
 * 当前用户配置视图（{@code 04} `API-USER-001` 响应 `data`）。
 *
 * <p>三条红线（{@code 03} §11.2）：
 * <ul>
 *   <li>`user_id` 为 `BIGINT`，对外必须是<b>十进制字符串</b> —— 用 {@link ToStringSerializer} 显式标注，
 *       而不是靠全局 `Long → String`（那会把 `INT` 计数也变成字符串）；</li>
 *   <li>**绝不**包含 `password_hash` / `*_api_key_cipher` / `key_version`；</li>
 *   <li>API Key 只以"是否已配置 + 掩码"形式出现（掩码由 Java 解密后只取首尾字符生成）。</li>
 * </ul>
 *
 * <p>`existing_course_count` 由 `API-USER-001` 与 `API-USER-003` 均返回；前端在修改默认
 * Embedding 目标前据此弹窗确认是否迁移已有课程。内部查询场景传 null 时整字段省略。
 */
public record UserConfigVO(
        @JsonSerialize(using = ToStringSerializer.class) Long userId,
        String username,
        String chatProvider,
        String chatBaseUrl,
        boolean hasChatApiKey,
        String chatApiKeyMasked,
        String chatModel,
        String defaultEmbeddingProvider,
        String embeddingBaseUrl,
        boolean hasEmbeddingApiKey,
        String embeddingApiKeyMasked,
        String defaultEmbeddingModel,
        Integer defaultEmbeddingDim,
        String timezone,
        String dndStartTime,
        String dndEndTime,
        String boundImPlatform,
        String boundImUid,
        String updatedAt,
        boolean hasByokConfig,
        /** 用户自带的 QQ 官方机器人 AppID（未配置为 null）。 */
        String qqbotAppId,
        /** 是否已保存 AppSecret（只回布尔，绝不回显密文）。 */
        boolean hasQqbotSecret,
        /** DISCONNECTED / CONNECTING / ONLINE / ERROR。 */
        String qqbotStatus,
        /** 最近一次连接失败原因（已脱敏）。 */
        String qqbotLastError,
        /** 是否已完成 openid 绑定（用户私聊过机器人）。 */
        boolean qqbotBound,
        /** 部署方是否统一提供向量模型（`SC_PLATFORM_EMBEDDING_*`）；前端据此标注「平台默认」。 */
        boolean platformEmbedding,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer existingCourseCount) {
}
