package com.scholarclaw.modules.channel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** TASK-803 入站去重键与 Redis 降级。 */
class OneBotInboundDedupTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private OneBotInboundDedup dedup;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        dedup = new OneBotInboundDedup(redisTemplate);
    }

    @Test
    @DisplayName("首次消息获得处理权，键格式为 QQ_ONEBOT:sender:message")
    void claimsFirstMessage() {
        when(valueOperations.setIfAbsent(
                eq("sc:idemp:msg:QQ_ONEBOT:24681012:188992211"),
                anyString(), eq(Duration.ofSeconds(60)))).thenReturn(true);

        OneBotInboundDedup.Claim claim = dedup.claim("24681012", "188992211");

        assertThat(claim.acquired()).isTrue();
        assertThat(claim.token()).isNotBlank();
    }

    @Test
    @DisplayName("重复消息不获得处理权")
    void rejectsDuplicate() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);

        assertThat(dedup.claim("24681012", "188992211").acquired()).isFalse();
    }

    @Test
    @DisplayName("Redis 不可用：降级放行，无 owner token")
    void degradesOpenWhenRedisUnavailable() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new IllegalStateException("redis down"));

        OneBotInboundDedup.Claim claim = dedup.claim("24681012", "188992211");

        assertThat(claim.acquired()).isTrue();
        assertThat(claim.token()).isNull();
        // 无 token 时标记/释放应为无操作，不抛异常。
        dedup.markProcessed("24681012", "188992211", claim);
        dedup.release("24681012", "188992211", claim);
    }
}
