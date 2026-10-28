package com.scholarclaw.modules.channel.service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * OneBot 入站消息幂等（TASK-803、{@code 02} §7.5）。
 *
 * <p>键 `sc:idemp:msg:QQ_ONEBOT:{sender_id}:{message_id}`：
 * {@code PROCESSING:<owner-token>} 60s → {@code PROCESSED} 24h。同步阶段只做 `SET NX`，
 * 保证 Webhook 快速 ACK；异步处理成功后用 owner token CAS 升级为 {@code PROCESSED}，
 * 失败则仅删除自己的键，允许 OneBot 重投且不会误删其他请求的新锁。
 *
 * <p>Redis 不可用时**降级放行**并记录 WARN：下游 DDL 请求级幂等键与文档 SHA-256 查重仍能兜底，
 * 不能因 Redis 故障让所有 IM 消息停摆。
 */
@Component
public class OneBotInboundDedup {

    private static final Logger log = LoggerFactory.getLogger(OneBotInboundDedup.class);
    private static final String KEY_PREFIX = "sc:idemp:msg:QQ_ONEBOT:";
    private static final Duration PROCESSING_TTL = Duration.ofSeconds(60);
    private static final Duration PROCESSED_TTL = Duration.ofHours(24);
    private static final String PROCESSING_PREFIX = "PROCESSING:";
    private static final String PROCESSED = "PROCESSED";

    /** 仅当仍是本轮 owner token 时才升级为 PROCESSED。 */
    private static final DefaultRedisScript<Long> MARK_PROCESSED_SCRIPT =
            new DefaultRedisScript<>(
                    "if redis.call('get', KEYS[1]) == ARGV[1] "
                            + "then redis.call('set', KEYS[1], ARGV[2], 'PX', ARGV[3]) return 1 "
                            + "else return 0 end",
                    Long.class);

    /** 仅当仍是本轮 owner token 时才删除。 */
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] "
                    + "then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redisTemplate;

    public OneBotInboundDedup(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** @return true 表示获得处理权；false 表示已存在 PROCESSING/PROCESSED 记录。 */
    public Claim claim(String senderId, String messageId) {
        String key = key(senderId, messageId);
        String token = UUID.randomUUID().toString();
        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(key, PROCESSING_PREFIX + token, PROCESSING_TTL);
            return Boolean.TRUE.equals(acquired) ? new Claim(true, token) : Claim.duplicate();
        } catch (RuntimeException e) {
            log.warn("OneBot 入站幂等 Redis 不可用，降级放行 senderId={}（下游幂等兜底）", senderId);
            return Claim.degraded();
        }
    }

    public void markProcessed(String senderId, String messageId, Claim claim) {
        if (claim == null || claim.token() == null) {
            return;
        }
        try {
            redisTemplate.execute(MARK_PROCESSED_SCRIPT,
                    List.of(key(senderId, messageId)),
                    PROCESSING_PREFIX + claim.token(), PROCESSED,
                    String.valueOf(PROCESSED_TTL.toMillis()));
        } catch (RuntimeException e) {
            log.warn("OneBot 入站幂等标记 PROCESSED 失败 senderId={}", senderId);
        }
    }

    public void release(String senderId, String messageId, Claim claim) {
        if (claim == null || claim.token() == null) {
            return;
        }
        try {
            redisTemplate.execute(RELEASE_SCRIPT, List.of(key(senderId, messageId)),
                    PROCESSING_PREFIX + claim.token());
        } catch (RuntimeException e) {
            log.warn("OneBot 入站幂等释放失败 senderId={}", senderId);
        }
    }

    static String key(String senderId, String messageId) {
        return KEY_PREFIX + senderId + ":" + messageId;
    }

    /** 一次领取结果；{@code token=null} 表示 Redis 降级（无锁可释放）。 */
    public record Claim(boolean acquired, String token) {

        static Claim duplicate() {
            return new Claim(false, null);
        }

        static Claim degraded() {
            return new Claim(true, null);
        }
    }
}
