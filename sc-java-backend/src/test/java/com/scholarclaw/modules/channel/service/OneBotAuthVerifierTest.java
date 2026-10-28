package com.scholarclaw.modules.channel.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TASK-803 Webhook Bearer 令牌常量时间校验。 */
class OneBotAuthVerifierTest {

    @Test
    @DisplayName("正确 Bearer 令牌通过，且 scheme 大小写不敏感")
    void acceptsCorrectToken() {
        OneBotAuthVerifier verifier = new OneBotAuthVerifier(" secret-token ");

        assertThat(verifier.isAuthorized("Bearer secret-token")).isTrue();
        assertThat(verifier.isAuthorized("bearer secret-token")).isTrue();
        assertThat(verifier.isAuthorized("  Bearer   secret-token  ")).isTrue();
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
    @DisplayName("未配置令牌：失败关闭")
    void failsClosedWhenTokenMissing() {
        assertThat(new OneBotAuthVerifier("").isAuthorized("Bearer anything")).isFalse();
        assertThat(new OneBotAuthVerifier(null).isAuthorized("Bearer anything")).isFalse();
    }
}
