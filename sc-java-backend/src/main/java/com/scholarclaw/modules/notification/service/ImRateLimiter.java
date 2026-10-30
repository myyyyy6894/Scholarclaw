package com.scholarclaw.modules.notification.service;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 外部 IM 主动发送的全局令牌桶（TASK-804、{@code NFR-SEC-01}、{@code 02} §8.2）。
 *
 * <p>Redis Hash `sc:ratelimit:im_push:global` 保存 `tokens` 与 `last_refill_ms`（字段名与
 * {@code 03} §8.2 一致），Lua 原子补充/扣减：容量默认 15，补充速率默认 10 条/分钟
 * （可在 10~15 区间配置），键 TTL 120s 滚动刷新。令牌不足时返回需要等待的毫秒数，
 * 由调度器把通知转为 `RATE_LIMITED_DELAYED` 并顺延，不在调度线程里睡眠等待。
 *
 * <p><b>故障策略：失败关闭（推迟）。</b>Redis 不可用时无法证明速率额度，返回一个固定延迟，
 * 让通知保持待推送而不是无上限发信；站内信已经落库，Web 端不受影响。
 */
@Component
public class ImRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(ImRateLimiter.class);

    static final String KEY = "sc:ratelimit:im_push:global";

    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> ACQUIRE_SCRIPT = new DefaultRedisScript<>(
            """
            local tokens = tonumber(redis.call('HGET', KEYS[1], 'tokens'))
            local lastRefillMs = tonumber(redis.call('HGET', KEYS[1], 'last_refill_ms'))
            local now = tonumber(ARGV[1])
            local capacity = tonumber(ARGV[2])
            local refill = tonumber(ARGV[3])
            local ttl = tonumber(ARGV[4])
            if tokens == nil then tokens = capacity; lastRefillMs = now end
            local delta = now - lastRefillMs
            if delta < 0 then delta = 0 end
            tokens = math.min(capacity, tokens + delta * refill)
            if tokens < 1 then
              local wait = math.ceil((1 - tokens) / refill)
              redis.call('HSET', KEYS[1], 'tokens', tokens, 'last_refill_ms', now)
              redis.call('EXPIRE', KEYS[1], ttl)
              return {0, wait}
            end
            tokens = tokens - 1
            redis.call('HSET', KEYS[1], 'tokens', tokens, 'last_refill_ms', now)
            redis.call('EXPIRE', KEYS[1], ttl)
            return {1, 0}
            """,
            List.class);

    private final StringRedisTemplate redisTemplate;
    private final int capacity;
    private final double refillPerMilli;
    private final long ttlSeconds;
    private final long degradedWaitMillis;

    public ImRateLimiter(StringRedisTemplate redisTemplate,
                         @Value("${sc.notification.im.rate-capacity:15}") int capacity,
                         @Value("${sc.notification.im.rate-per-minute:10}") int ratePerMinute,
                         @Value("${sc.notification.im.rate-key-ttl-seconds:120}") long ttlSeconds,
                         @Value("${sc.notification.im.redis-degraded-wait-ms:10000}")
                         long degradedWaitMillis) {
        this.redisTemplate = redisTemplate;
        this.capacity = Math.max(1, capacity);
        int rate = Math.max(1, ratePerMinute);
        this.refillPerMilli = rate / 60_000.0;
        this.ttlSeconds = Math.max(1, ttlSeconds);
        this.degradedWaitMillis = Math.max(1000, degradedWaitMillis);
    }

    public AcquireResult tryAcquire() {
        long now = System.currentTimeMillis();
        try {
            List<?> result = redisTemplate.execute(ACQUIRE_SCRIPT, List.of(KEY),
                    String.valueOf(now),
                    String.valueOf(capacity),
                    String.valueOf(refillPerMilli),
                    String.valueOf(ttlSeconds));
            if (result == null || result.size() < 2) {
                return AcquireResult.denied(degradedWaitMillis);
            }
            long allowed = toLong(result.get(0));
            long wait = toLong(result.get(1));
            return allowed == 1L ? AcquireResult.granted() : AcquireResult.denied(wait);
        } catch (RuntimeException e) {
            log.warn("IM 令牌桶 Redis 不可用，失败关闭并推迟发送 error={}",
                    e.getClass().getSimpleName());
            return AcquireResult.denied(degradedWaitMillis);
        }
    }

    private static long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    /** 一次令牌桶结果；{@code waitMillis} 仅在拒绝时有意义。 */
    public record AcquireResult(boolean allowed, long waitMillis) {

        static AcquireResult granted() {
            return new AcquireResult(true, 0L);
        }

        static AcquireResult denied(long waitMillis) {
            return new AcquireResult(false, Math.max(1000L, waitMillis));
        }
    }
}
