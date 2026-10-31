package com.scholarclaw.modules.auth.service;

import com.scholarclaw.common.security.AccountDeactivationKey;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * JWT 吊销黑名单与账号注销阻断键（{@code 03} §8.1 第 5、6 项；`auth` 模块的 Redis 唯一写入权）。
 *
 * <p>键格式：
 * <ul>
 *   <li>黑名单：`sc:u:{user_id}:jwt_blacklist:{jti}`，TTL = 该 token 的剩余有效期（到期自然淘汰，零泄漏）；</li>
 *   <li>注销阻断：`sc:u:{user_id}:deactivated`，TTL = 7 天（覆盖所有已签发 JWT 的最大生命周期）。</li>
 * </ul>
 *
 * <p>过滤器每次都查这两个键（一次 `MGET` 只占一个往返）。**Redis 故障时不吞异常**：
 * 黑名单不可用意味着无法判断 token 是否已被吊销，此时放行等于让"已登出的 token"复活——
 * 由调用方按失败关闭处理（返回 500 + trace_id，而不是伪装成 401 让客户端误解）。
 */
@Service
public class TokenRevocationService {

    private static final String BLACKLIST_KEY = "sc:u:%d:jwt_blacklist:%s";
    private static final long DEACTIVATED_TTL_SECONDS = AccountDeactivationKey.TTL.toSeconds();

    private final StringRedisTemplate redisTemplate;

    public TokenRevocationService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** 登出：把当前 `jti` 写入黑名单，TTL 取 token 剩余有效期（已过期则无需写入）。 */
    public void revoke(Long userId, String tokenId, Instant expiresAt) {
        long remaining = Duration.between(Instant.now(), expiresAt).toSeconds();
        if (remaining <= 0) {
            return;
        }
        redisTemplate.opsForValue().set(blacklistKey(userId, tokenId), "1", Duration.ofSeconds(remaining));
    }

    /**
     * 一次往返查完"是否已吊销 + 账号是否已注销"。
     *
     * @return 判定结果；调用方据 {@link TokenStatus} 决定是否放行
     */
    public TokenStatus check(Long userId, String tokenId) {
        List<String> values = redisTemplate.opsForValue()
                .multiGet(List.of(blacklistKey(userId, tokenId), deactivatedKey(userId)));
        boolean revoked = values != null && !values.isEmpty() && values.get(0) != null;
        boolean deactivated = values != null && values.size() > 1 && values.get(1) != null;
        return new TokenStatus(revoked, deactivated);
    }

    /** 账号是否已进入注销阻断（IM 网关等非 JWT 入口也用）。 */
    public boolean isDeactivated(Long userId) {
        return redisTemplate.hasKey(deactivatedKey(userId));
    }

    /**
     * 写入注销阻断键（TASK-901 的注销流程会调用；此处只提供写入能力，不在 TASK-202 触发）。
     */
    public void markDeactivated(Long userId) {
        redisTemplate.opsForValue()
                .set(deactivatedKey(userId), "1", Duration.ofSeconds(DEACTIVATED_TTL_SECONDS));
    }

    private static String blacklistKey(Long userId, String tokenId) {
        return BLACKLIST_KEY.formatted(userId, tokenId);
    }

    private static String deactivatedKey(Long userId) {
        return AccountDeactivationKey.of(userId);
    }

    /** token 可用性判定结果。 */
    public record TokenStatus(boolean revoked, boolean deactivated) {
    }
}
