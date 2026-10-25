package com.scholarclaw.modules.ddl.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * DDL 请求级幂等键（{@code 03} §4.5 / {@code 04} API-DDL-002）。
 *
 * <p>固定公式：{@code sha256(user_id + ":" + source_platform + ":" + source_msg_id + ":DDL")}。
 * Web 手动建单的 {@code source_msg_id} = {@code client_request_id}；同一次操作超时重试必须复用
 * 原 ID，因此键完全相同，由 {@code uk_ddl_idempotency_key} 兜底。{@code force_create} 不改变公式。
 */
public final class DdlIdempotencyKey {

    public static final String SOURCE_PLATFORM_WEB = "WEB";

    private DdlIdempotencyKey() {
    }

    public static String forWebRequest(Long userId, String clientRequestId) {
        return sha256(userId + ":" + SOURCE_PLATFORM_WEB + ":" + clientRequestId + ":DDL");
    }

    public static String forRequest(Long userId, String sourcePlatform, String sourceMsgId) {
        return sha256(userId + ":" + sourcePlatform + ":" + sourceMsgId + ":DDL");
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // JVM 必须提供 SHA-256；缺失属于平台级错误，不能静默降级为其他算法。
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }
}
