package com.scholarclaw.modules.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scholarclaw.common.security.ScUserPrincipal;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * JWT 签发与验签（{@code 04} §2.7.2）。
 *
 * <p>重点是**否定用例**：伪造、篡改、换密钥、过期、错签发方都必须返回空，而不是"解析出一个用户"。
 */
class JwtServiceTest {

    private static final String SECRET = "unit-test-only-jwt-secret-0123456789abcdef";
    private static final Long USER_ID = 10001L;

    private final JwtService jwtService = new JwtService(SECRET, 604800);

    @Test
    @DisplayName("签发后能验签，claims 与契约一致（sub=user_id、username、role、jti、exp）")
    void issuedTokenRoundTrips() {
        JwtService.IssuedToken issued = jwtService.issue(USER_ID, "zhangsan_cs", "STUDENT");

        Optional<ScUserPrincipal> verified = jwtService.verify(issued.token());

        assertThat(verified).isPresent();
        ScUserPrincipal principal = verified.get();
        assertThat(principal.userId()).isEqualTo(USER_ID);
        assertThat(principal.username()).isEqualTo("zhangsan_cs");
        assertThat(principal.role()).isEqualTo("STUDENT");
        assertThat(principal.tokenId()).isEqualTo(issued.tokenId());
        assertThat(principal.expiresAt()).isEqualTo(issued.expiresAt());
        assertThat(issued.expiresInSeconds()).isEqualTo(604800L);
        assertThat(issued.token()).hasSizeGreaterThan(40).contains(".");
    }

    @Test
    @DisplayName("换一个密钥签发的 token 验签失败（不能靠猜密钥伪造身份）")
    void tokenSignedByAnotherKeyIsRejected() {
        JwtService attacker = new JwtService("another-attacker-secret-0123456789abcdef", 604800);

        JwtService.IssuedToken forged = attacker.issue(USER_ID, "attacker", "STUDENT");

        assertThat(jwtService.verify(forged.token())).isEmpty();
    }

    @Test
    @DisplayName("篡改 payload 会导致签名校验失败")
    void tamperedTokenIsRejected() {
        String token = jwtService.issue(USER_ID, "zhangsan_cs", "STUDENT").token();
        String[] parts = token.split("\\.");
        String tamperedPayload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"99999\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        String tampered = parts[0] + "." + tamperedPayload + "." + parts[2];

        assertThat(jwtService.verify(tampered)).isEmpty();
    }

    @Test
    @DisplayName("过期 token 被拒绝（用 0 秒 TTL 构造）")
    void expiredTokenIsRejected() {
        JwtService shortLived = new JwtService(SECRET, 0);
        String token = shortLived.issue(USER_ID, "zhangsan_cs", "STUDENT").token();

        assertThat(shortLived.verify(token)).isEmpty();
    }

    @Test
    @DisplayName("空串、乱码、null 一律返回空")
    void malformedTokensAreRejected() {
        assertThat(jwtService.verify(null)).isEmpty();
        assertThat(jwtService.verify("")).isEmpty();
        assertThat(jwtService.verify("   ")).isEmpty();
        assertThat(jwtService.verify("not-a-jwt")).isEmpty();
        assertThat(jwtService.verify("a.b.c")).isEmpty();
    }

    @Test
    @DisplayName("密钥缺失或过短时拒绝创建（启动即失败，不降级为弱密钥）")
    void rejectsMissingOrShortSecret() {
        assertThatThrownBy(() -> new JwtService("", 604800))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SC_JWT_SECRET");
        assertThatThrownBy(() -> new JwtService("short-secret", 604800))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("长度不足");
    }
}
