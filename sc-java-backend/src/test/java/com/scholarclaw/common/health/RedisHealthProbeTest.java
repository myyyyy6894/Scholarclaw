package com.scholarclaw.common.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 连接池级探针断言（{@code 04} §4.9.8、RISK-25）。
 *
 * <p>口令错误在真实环境里表现为连接阶段的认证异常——必须被吞掉按 DOWN 上报，
 * 否则健康接口会跟着 500，"依赖挂了导致健康检查也挂了"。
 */
class RedisHealthProbeTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final RedisHealthProbe probe = new RedisHealthProbe(redisTemplate);

    @Test
    @DisplayName("PING 返回 PONG → UP；名称/角色固定为 redis / 自身依赖")
    @SuppressWarnings("unchecked")
    void reportsUpOnPong() {
        when(redisTemplate.execute(any(RedisCallback.class))).thenReturn("PONG");

        assertThat(probe.isUp()).isTrue();
        assertThat(probe.name()).isEqualTo("redis");
        assertThat(probe.kind()).isEqualTo(HealthProbe.Kind.DEPENDENCY);
    }

    @Test
    @DisplayName("连接失败（口令错误/不可达）或返回非 PONG → DOWN，不抛异常")
    @SuppressWarnings("unchecked")
    void reportsDownOnFailure() {
        when(redisTemplate.execute(any(RedisCallback.class)))
                .thenThrow(new RedisConnectionFailureException("NOAUTH Authentication required."));
        assertThat(probe.isUp()).isFalse();

        when(redisTemplate.execute(any(RedisCallback.class))).thenReturn(null);
        assertThat(probe.isUp()).isFalse();
    }
}
