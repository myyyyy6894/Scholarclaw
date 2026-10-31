package com.scholarclaw.modules.userconfig.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.scholarclaw.common.event.UserCleanupCompletedEvent;
import com.scholarclaw.common.security.AccountDeactivationKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

class UserCleanupRegistryTest {

    private static final Long USER_ID = 10001L;

    private StringRedisTemplate redisTemplate;
    private SetOperations<String, String> setOps;
    private UserCleanupRegistry registry;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        setOps = mock(SetOperations.class);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        registry = new UserCleanupRegistry(redisTemplate);
    }

    @Test
    @DisplayName("模块未全部到齐：返回 false")
    void notCompleteUntilAllModules() {
        when(setOps.size(anyString())).thenReturn(3L);

        assertThat(registry.record(USER_ID, UserCleanupCompletedEvent.Module.KNOWLEDGE))
                .isFalse();
    }

    @Test
    @DisplayName("全部模块到齐：返回 true")
    void completeWhenAllModules() {
        when(setOps.size(anyString())).thenReturn(
                (long) UserCleanupCompletedEvent.Module.values().length);

        assertThat(registry.record(USER_ID, UserCleanupCompletedEvent.Module.COURSE)).isTrue();
    }

    @Test
    @DisplayName("Redis 故障：返回 false，不误判完成")
    void redisFailureIsNotComplete() {
        when(setOps.add(anyString(), anyString())).thenThrow(new IllegalStateException("down"));

        assertThat(registry.record(USER_ID, UserCleanupCompletedEvent.Module.DDL)).isFalse();
    }

    @Test
    @DisplayName("登记键使用注销 TTL")
    void usesDeactivationTtl() {
        when(setOps.size(anyString())).thenReturn(1L);

        registry.record(USER_ID, UserCleanupCompletedEvent.Module.AGENT);

        org.mockito.Mockito.verify(redisTemplate).expire(
                eq("sc:u:" + USER_ID + ":cleanup:modules"), eq(AccountDeactivationKey.TTL));
    }
}
