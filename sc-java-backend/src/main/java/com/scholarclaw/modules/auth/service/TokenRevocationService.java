package com.scholarclaw.modules.auth.service;

import com.scholarclaw.common.security.AccountDeactivationKey;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * JWT 吊销黑名单与账号注销阻断键（{@code 03} §8.1 第 5、6 项；`auth` 模块的 Redis 唯一写入权）。
 *
 * <p>键格式：
 * <ul>
 *   <li>黑名单：`sc:u:{user_id}:jwt_blacklist:{jti}`，TTL = 该 token 的剩余有效期；</li>
 *   <li>注销阻断：`sc:u:{user_id}:deactivated`，TTL = `max(7 天, JWT TTL + 1 天)`；</li>
 *   <li>活跃负缓存：`sc:u:{user_id}:active`，TTL 30s，避免每请求查库；注销流程会删除它。</li>
 * </ul>
 *
 * <p><b>数据库兜底（P9 复核第 5 项）：</b>阻断键可能因 TTL 到期/Redis 清理而缺失；此时
 * 过滤器必须查一次 `t_user_config.account_status`（或用户行是否仍存在），不能把“键不在”
 * 当成“账号正常”。命中 DEACTIVATING/缺失时立即补写阻断键并拒绝请求。
 *
 * <p><b>Redis 故障失败关闭：</b>黑名单查不了就无法证明 token 未被吊销，不能放行。
 */
@Service
public class TokenRevocationService {

    private static final Logger log = LoggerFactory.getLogger(TokenRevocationService.class);

    private static final String BLACKLIST_KEY = "sc:u:%d:jwt_blacklist:%s";

    private final StringRedisTemplate redisTemplate;
    private final UserConfigService userConfigService;
    private final long deactivatedTtlSeconds;
    private final Duration activeCacheTtl;

    public TokenRevocationService(
            StringRedisTemplate redisTemplate,
            UserConfigService userConfigService,
            @Value("${sc.jwt.ttl-seconds:604800}") long jwtTtlSeconds,
            @Value("${sc.auth.account-active-cache-seconds:30}") long activeCacheSeconds) {
        this.redisTemplate = redisTemplate;
        this.userConfigService = userConfigService;
        this.deactivatedTtlSeconds = AccountDeactivationKey.ttlFor(jwtTtlSeconds).toSeconds();
        this.activeCacheTtl = Duration.ofSeconds(Math.max(5, activeCacheSeconds));
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
     * 一次往返查完“是否已吊销 + 账号是否已注销”；注销键未命中且无活跃负缓存时查库兜底。
     *
     * @return 判定结果；调用方据 {@link TokenStatus} 决定是否放行
     */
    public TokenStatus check(Long userId, String tokenId) {
        List<String> values = redisTemplate.opsForValue().multiGet(List.of(
                blacklistKey(userId, tokenId), deactivatedKey(userId), activeMarkerKey(userId)));
        boolean revoked = values != null && !values.isEmpty() && values.get(0) != null;
        boolean deactivated = values != null && values.size() > 1 && values.get(1) != null;
        boolean recentlyActive = values != null && values.size() > 2 && values.get(2) != null;
        if (!deactivated && !recentlyActive) {
            boolean blocked = userConfigService.isAccountDeactivatedOrMissing(userId);
            if (blocked) {
                deactivated = true;
                markDeactivatedQuietly(userId);
            } else {
                cacheActiveQuietly(userId);
            }
        }
        return new TokenStatus(revoked, deactivated);
    }

    /** 账号是否已进入注销阻断（IM 网关等非 JWT 入口也用；同样有数据库兜底）。 */
    public boolean isDeactivated(Long userId) {
        if (Boolean.TRUE.equals(redisTemplate.hasKey(deactivatedKey(userId)))) {
            return true;
        }
        if (Boolean.TRUE.equals(redisTemplate.hasKey(activeMarkerKey(userId)))) {
            return false;
        }
        boolean blocked = userConfigService.isAccountDeactivatedOrMissing(userId);
        if (blocked) {
            markDeactivatedQuietly(userId);
        } else {
            cacheActiveQuietly(userId);
        }
        return blocked;
    }

    /**
     * 写入注销阻断键并清除活跃负缓存；TTL 按实际 JWT 有效期动态计算。
     */
    public void markDeactivated(Long userId) {
        redisTemplate.opsForValue()
                .set(deactivatedKey(userId), "1", Duration.ofSeconds(deactivatedTtlSeconds));
        redisTemplate.delete(activeMarkerKey(userId));
    }

    private void markDeactivatedQuietly(Long userId) {
        try {
            markDeactivated(userId);
        } catch (RuntimeException e) {
            // 判定已经完成；补写失败不应把“已注销”误判成 500，下一请求会再次兜底。
            log.warn("注销阻断键补写失败 userId={} error={}", userId,
                    e.getClass().getSimpleName());
        }
    }

    private void cacheActiveQuietly(Long userId) {
        try {
            redisTemplate.opsForValue().set(activeMarkerKey(userId), "1", activeCacheTtl);
        } catch (RuntimeException e) {
            log.debug("活跃负缓存写入失败 userId={} error={}", userId,
                    e.getClass().getSimpleName());
        }
    }

    private static String blacklistKey(Long userId, String tokenId) {
        return BLACKLIST_KEY.formatted(userId, tokenId);
    }

    private static String deactivatedKey(Long userId) {
        return AccountDeactivationKey.of(userId);
    }

    private static String activeMarkerKey(Long userId) {
        return AccountDeactivationKey.activeMarker(userId);
    }

    /** token 可用性判定结果。 */
    public record TokenStatus(boolean revoked, boolean deactivated) {
    }
}
