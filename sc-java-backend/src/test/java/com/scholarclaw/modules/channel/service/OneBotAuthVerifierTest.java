package com.scholarclaw.modules.channel.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TASK-803 Webhook Bearer 与 NapCat X-Signature（HMAC-SHA1）校验。 */
class OneBotAuthVerifierTest {

    /** 常见 HMAC-SHA1 测试向量：key=key，data=The quick brown fox... */
    private static final String HMAC_VECTOR =
            "sha1=de7c9b85b8b78aa6bc8a7a36f70a90701c9db4d9";
    private static final byte[] HMAC_BODY =
            "The quick brown fox jumps over the lazy dog".getBytes(StandardCharsets.UTF_8);

    @Test
    @DisplayName("正确 Bearer 令牌通过，且 scheme 大小写不敏感")
    void acceptsCorrectToken() {
        OneBotAuthVerifier verifier = new OneBotAuthVerifier(" secret-token ");

        assertThat(verifier.isAuthorized("Bearer secret-token")).isTrue();
        assertThat(verifier.isAuthorized("bearer secret-token")).isTrue();
        assertThat(verifier.isAuthorized("  Bearer   secret-token  ")).isTrue();
    }

    @Test
    @DisplayName("NapCat X-Signature：HMAC-SHA1 正确通过")
    void acceptsNapcatHmacSignature() {
        OneBotAuthVerifier verifier = new OneBotAuthVerifier("key");

        assertThat(verifier.isAuthorized(null, HMAC_VECTOR, HMAC_BODY)).isTrue();
        assertThat(verifier.isAuthorized(null,
                "SHA1=DE7C9B85B8B78AA6BC8A7A36F70A90701C9DB4D9", HMAC_BODY)).isTrue();
    }

    @Test
    @DisplayName("NapCat X-Signature：篡改 body、错误签名、畸形头一律拒绝")
    void rejectsInvalidHmacSignature() {
        OneBotAuthVerifier verifier = new OneBotAuthVerifier("key");

        assertThat(verifier.isAuthorized(null, HMAC_VECTOR,
                "tampered".getBytes(StandardCharsets.UTF_8))).isFalse();
        assertThat(verifier.isAuthorized(null,
                "sha1=0000000000000000000000000000000000000000", HMAC_BODY)).isFalse();
        assertThat(verifier.isAuthorized(null, "sha1=not-hex", HMAC_BODY)).isFalse();
        assertThat(verifier.isAuthorized(null, "sha256=" + "0".repeat(64), HMAC_BODY))
                .isFalse();
        assertThat(verifier.isAuthorized(null, HMAC_VECTOR, null)).isFalse();
        assertThat(verifier.isAuthorized(null, HMAC_VECTOR, new byte[0])).isFalse();
    }

    @Test
    @DisplayName("缺失、错误 scheme、错误令牌一律拒绝")
    void rejectsInvalidToken() {
        OneBotAuthVerifier verifier = new OneBotAuthVerifier("secret-token");

        assertThat(verifier.isAuthorized(null)).isFalse();
        assertThat(verifier.isAuthorized("")).isFalse();
        assertThat(verifier.isAuthorized("secret-token")).isFalse();
        assertThat(verifier.isAuthorized("Basic secret-token")).isFalse();
        assertThat(verifier.isAuthorized("Bearer wrong")).isFalse();
        assertThat(verifier.isAuthorized("Bearer ")).isFalse();
    }

    @Test
    @DisplayName("未配置令牌：失败关闭，Bearer 与签名都不放行")
    void failsClosedWhenTokenMissing() {
        assertThat(new OneBotAuthVerifier("").isAuthorized("Bearer anything")).isFalse();
        assertThat(new OneBotAuthVerifier(null).isAuthorized("Bearer anything")).isFalse();
        assertThat(new OneBotAuthVerifier("").isAuthorized(null, HMAC_VECTOR, HMAC_BODY))
                .isFalse();
    }
}
