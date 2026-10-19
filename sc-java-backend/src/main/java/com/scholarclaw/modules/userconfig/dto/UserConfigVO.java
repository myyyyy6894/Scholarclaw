package com.scholarclaw.modules.userconfig.dto;

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
 *   <li>API Key 只以"是否已配置 + 掩码"形式出现（掩码需要解密，属 TASK-203；
 *       本阶段没有写入路径，故 `has_* = false`、掩码为 `null`）。</li>
 * </ul>
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
        String updatedAt) {
}
