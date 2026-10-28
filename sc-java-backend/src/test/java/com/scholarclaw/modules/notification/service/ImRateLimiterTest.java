package com.scholarclaw.modules.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

/** TASK-804 Redis 令牌桶：允许、拒绝等待与 Redis 故障失败关闭。 */
class ImRateLimiterTest {

    private StringRedisTemplate redisTemplate;
    private ImRateLimiter limiter;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        limiter = new ImRateLimiter(redisTemplate, 15, 10, 120, 10_000);
    }

    @Test
    @DisplayName("令牌充足：允许发送")
    @SuppressWarnings("unchecked")
    void allowsWhenTokenAvailable() {
        when(redisTemplate.execute(any(), anyList(), any(), any(), any(), any()))
                .thenReturn(List.of(1L, 0L));

        assertThat(limiter.tryAcquire().allowed()).isTrue();
        assertThat(limiter.tryAcquire().waitMillis()).isZero();
    }

    @Test
    @DisplayName("令牌不足：返回 Lua 计算的等待毫秒数")
    @SuppressWarnings("unchecked")
    void deniesWithWait() {
        when(redisTemplate.execute(any(), anyList(), any(), any(), any(), any()))
                .thenReturn(List.of(0L, 6000L));

        ImRateLimiter.AcquireResult result = limiter.tryAcquire();

        assertThat(result.allowed()).isFalse();
        assertThat(result.waitMillis()).isEqualTo(6000L);
    }

    @Test
    @DisplayName("Redis 异常：失败关闭，返回固定退避而不是放行")
    @SuppressWarnings("unchecked")
    void failsClosedWhenRedisUnavailable() {
        when(redisTemplate.execute(any(), anyList(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("redis down"));

        ImRateLimiter.AcquireResult result = limiter.tryAcquire();

        assertThat(result.allowed()).isFalse();
        assertThat(result.waitMillis()).isEqualTo(10_000L);
    }

    @Test
    @DisplayName("脚本返回异常结构：失败关闭")
    @SuppressWarnings("unchecked")
    void failsClosedOnMalformedScriptResult() {
        when(redisTemplate.execute(any(), anyList(), any(), any(), any(), any()))
                .thenReturn(List.of());

        assertThat(limiter.tryAcquire().allowed()).isFalse();
    }
}
