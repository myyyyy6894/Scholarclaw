package com.scholarclaw.modules.auth.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

/**
 * 注册/登录成功响应（{@code 04} `API-AUTH-001` / `API-AUTH-002` 的 `data`，两者结构一致）。
 *
 * <p>`user_id` 为 `BIGINT`，对外必须是十进制字符串（{@code 03} §4.0.5 / {@code 04} §2.6）。
 */
public record AuthTokenResponse(
        @JsonSerialize(using = ToStringSerializer.class) Long userId,
        String username,
        String accountStatus,
        String accessToken,
        String tokenType,
        long expiresIn,
        boolean hasByokConfig,
        String createdAt) {
}
