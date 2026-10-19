package com.scholarclaw.common.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 连接池级健康探针（{@code 04} §4.9.8「检查 MySQL、Redis、RabbitMQ 连接池存活」）。
 *
 * <p>比 TCP 可达性多证明"能真正认证并执行命令"：口令错误、`requirepass` 不匹配、
 * 实例只读或已满载拒绝连接，TCP 探针都发现不了（RISK-25）。
 *
 * <p>只用 `PING`（无副作用、不写业务键），并显式读回 `PONG`——`execute` 返回 null 说明连接已断。
 */
public class RedisHealthProbe implements HealthProbe {

    private static final Logger log = LoggerFactory.getLogger(RedisHealthProbe.class);

    private final StringRedisTemplate redisTemplate;

    public RedisHealthProbe(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public String name() {
        return "redis";
    }

    @Override
    public Kind kind() {
        return Kind.DEPENDENCY;
    }

    @Override
    public boolean isUp() {
        try {
            String reply = redisTemplate.execute(RedisConnection::ping);
            return "PONG".equalsIgnoreCase(reply);
        } catch (RuntimeException e) {
            // 只记异常类型：消息里可能带 host/端口或口令片段
            log.warn("Redis 连接池探针失败 error={}", e.getClass().getSimpleName());
            return false;
        }
    }
}
