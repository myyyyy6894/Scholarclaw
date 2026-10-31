package com.scholarclaw.modules.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scholarclaw.common.security.AccountDeactivationKey;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** JWT 注销阻断：Redis 未命中时的数据库兜底与动态 TTL（P9 复核第 5 项）。 */
@ExtendWith(MockitoExtension.class)
class TokenRevocationServiceTest {

    private static final Long USER_ID = 10001L;
    private static final String JTI = "jti-1";
    private static final long JWT_TTL_SECONDS = 7L * 24 * 3600;

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private UserConfigService userConfigService;

    private TokenRevocationService service;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new TokenRevocationService(redisTemplate, userConfigService,
                JWT_TTL_SECONDS, 30);
    }

    @Test
    @DisplayName("Redis 未命中且数据库为 DEACTIVATING：拒绝并补写阻断键")
    void dbFallbackBlocksDeactivatingUser() {
        when(valueOps.multiGet(any())).thenReturn(
                java.util.Arrays.asList(null, null, null));
        when(userConfigService.isAccountDeactivatedOrMissing(USER_ID)).thenReturn(true);

        var status = service.check(USER_ID, JTI);

        assertThat(status.deactivated()).isTrue();
        verify(valueOps).set(eq(AccountDeactivationKey.of(USER_ID)), eq("1"),
                eq(AccountDeactivationKey.ttlFor(JWT_TTL_SECONDS)));
        verify(redisTemplate).delete(AccountDeactivationKey.activeMarker(USER_ID));
    }

    @Test
    @DisplayName("Redis 未命中且数据库正常：放行并写短期活跃负缓存，避免每请求查库")
    void dbFallbackAllowsActiveUserAndCaches() {
        when(valueOps.multiGet(any())).thenReturn(
                java.util.Arrays.asList(null, null, null));
        when(userConfigService.isAccountDeactivatedOrMissing(USER_ID)).thenReturn(false);

        var status = service.check(USER_ID, JTI);

        assertThat(status.deactivated()).isFalse();
        verify(valueOps).set(eq(AccountDeactivationKey.activeMarker(USER_ID)), eq("1"),
                eq(Duration.ofSeconds(30)));
    }

    @Test
    @DisplayName("活跃负缓存命中：不查数据库")
    void activeMarkerSkipsDatabase() {
        when(valueOps.multiGet(any())).thenReturn(
                java.util.Arrays.asList(null, null, "1"));

        var status = service.check(USER_ID, JTI);

        assertThat(status.deactivated()).isFalse();
        verify(userConfigService, never()).isAccountDeactivatedOrMissing(any());
    }

    @Test
    @DisplayName("isDeactivated 同样有数据库兜底（IM 网关等非 JWT 入口）")
    void isDeactivatedFallsBackToDatabase() {
        when(redisTemplate.hasKey(AccountDeactivationKey.of(USER_ID))).thenReturn(false);
        when(redisTemplate.hasKey(AccountDeactivationKey.activeMarker(USER_ID)))
                .thenReturn(false);
        when(userConfigService.isAccountDeactivatedOrMissing(USER_ID)).thenReturn(true);

        assertThat(service.isDeactivated(USER_ID)).isTrue();
        verify(valueOps).set(eq(AccountDeactivationKey.of(USER_ID)), eq("1"), any());
    }

    @Test
    @DisplayName("JWT TTL 超过 7 天：阻断键 TTL 动态延长")
    void ttlCoversLongerJwt() {
        long longJwt = 30L * 24 * 3600;
        TokenRevocationService longLived = new TokenRevocationService(redisTemplate,
                userConfigService, longJwt, 30);

        longLived.markDeactivated(USER_ID);

        verify(valueOps).set(eq(AccountDeactivationKey.of(USER_ID)), eq("1"),
                eq(Duration.ofSeconds(longJwt + Duration.ofDays(1).toSeconds())));
    }
}
