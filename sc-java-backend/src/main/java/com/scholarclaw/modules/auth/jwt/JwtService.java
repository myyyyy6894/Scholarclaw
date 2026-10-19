package com.scholarclaw.modules.auth.jwt;

import com.scholarclaw.common.security.ScUserPrincipal;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * JWT 签发与验签（{@code 04} §2.7.1 / §2.7.2）。
 *
 * <p><b>Claims 契约（`04` §2.7.2）：</b>`sub` = `user_id` 十进制字符串、`username`、`role`、`jti`、`iat`、`exp`；
 * 有效期默认 7 天（`expires_in = 604800`）。使用 HS256 + `SC_JWT_SECRET`（仅 Java 容器持有）。
 *
 * <p><b>失败关闭：</b>密钥未注入或短于 32 字节时**启动即失败**，绝不"降级成不签名/弱密钥"——
 * 否则部署时漏注入一个变量就等于所有 token 可被伪造。
 *
 * <p>用 `jjwt` 而不是自己拼 Base64URL + HMAC：算法混淆、填充、声明校验这些坑不值得自研。
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    /** `04` §2.7.2 的签发方，验签时强校验（防止拿别的系统的 token 来过）。 */
    static final String ISSUER = "sc-java-backend";
    /** HS256 的最小密钥长度（字节）。 */
    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey signingKey;
    private final Duration ttl;

    public JwtService(@Value("${sc.jwt.secret:}") String secret,
                      @Value("${sc.jwt.ttl-seconds:604800}") long ttlSeconds) {
        String value = secret == null ? "" : secret.trim();
        if (value.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "SC_JWT_SECRET 未注入或长度不足 " + MIN_SECRET_BYTES + " 字节，拒绝启动（04 §2.7.1 失败关闭）");
        }
        this.signingKey = Keys.hmacShaKeyFor(value.getBytes(StandardCharsets.UTF_8));
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    /** 签发新 token（登录/注册成功后调用）。 */
    public IssuedToken issue(Long userId, String username, String role) {
        Instant issuedAt = Instant.now();
        // JWT 的 exp 只有秒精度：这里先截断，保证返回给调用方的 expiresAt 与 token 内的一致
        Instant expiresAt = issuedAt.plus(ttl).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        String tokenId = UUID.randomUUID().toString();
        String token = Jwts.builder()
                .issuer(ISSUER)
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("role", role)
                .id(tokenId)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
        return new IssuedToken(token, tokenId, expiresAt, ttl.toSeconds());
    }

    /**
     * 验签并解析 token。
     *
     * @return 验签通过的身份；签名无效/已过期/签发方不符时返回空（由调用方决定返回哪个错误码）
     */
    public Optional<ScUserPrincipal> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(ISSUER)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            Long userId = Long.valueOf(claims.getSubject());
            return Optional.of(new ScUserPrincipal(
                    userId,
                    claims.get("username", String.class),
                    claims.get("role", String.class),
                    claims.getId(),
                    claims.getExpiration().toInstant()));
        } catch (ExpiredJwtException e) {
            log.debug("JWT 已过期 subject={}", e.getClaims() == null ? "unknown" : e.getClaims().getSubject());
            return Optional.empty();
        } catch (JwtException | IllegalArgumentException e) {
            // 只记异常类型：token 片段属于凭据，不得进日志（03 §7.5）
            log.debug("JWT 验签失败 error={}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** 签发结果：token 本身 + 登出写黑名单需要的 `jti` 与过期时刻。 */
    public record IssuedToken(String token, String tokenId, Instant expiresAt, long expiresInSeconds) {
    }
}
