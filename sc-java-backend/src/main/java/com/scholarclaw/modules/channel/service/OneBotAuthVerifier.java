package com.scholarclaw.modules.channel.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * OneBot Webhook 令牌校验（TASK-803、{@code 04} §4.8.1）。
 *
 * <p>只接受 `Authorization: Bearer <SC_ONEBOT_ACCESS_TOKEN>`；令牌未注入时**失败关闭**，
 * 与内部服务令牌策略一致。比较使用常量时间，避免按字节时序泄露。
 */
@Component
public class OneBotAuthVerifier {

    private static final String BEARER = "bearer ";

    private final byte[] expectedToken;

    public OneBotAuthVerifier(@Value("${sc.onebot.access-token:}") String accessToken) {
        this.expectedToken = accessToken == null ? new byte[0]
                : accessToken.trim().getBytes(StandardCharsets.UTF_8);
    }

    public boolean isAuthorized(String authorizationHeader) {
        if (expectedToken.length == 0 || authorizationHeader == null) {
            return false;
        }
        String header = authorizationHeader.trim();
        if (header.length() <= BEARER.length()
                || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return false;
        }
        byte[] actual = header.substring(BEARER.length()).trim().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedToken, actual);
    }
}
