package com.scholarclaw.modules.userconfig.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scholarclaw.common.event.UserDeactivatingEvent;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.common.security.AccountDeactivationKey;
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import com.scholarclaw.modules.userconfig.mapper.UserConfigMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.time.Duration;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class UserDeactivationServiceTest {

    private static final Long USER_ID = 10001L;
    private static final long JWT_TTL_SECONDS = 7L * 24 * 3600;

    private UserConfigMapper mapper;
    private PasswordEncoder passwordEncoder;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private ApplicationEventPublisher publisher;
    private UserDeactivationService service;

    /**
     * 显式注册 MyBatis-Plus 表元数据：{@code lambdaUpdate().set(...)} 依赖该缓存。
     * 全量测试恰好被其他测试先初始化，单独运行本类会失败（P9 复核第 8 项）。
     */
    @BeforeAll
    static void initMybatisTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                UserConfigEntity.class);
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mapper = mock(UserConfigMapper.class);
        passwordEncoder = mock(PasswordEncoder.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        publisher = mock(ApplicationEventPublisher.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new UserDeactivationService(mapper, passwordEncoder, redisTemplate, publisher,
                JWT_TTL_SECONDS);
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("确认口令不正确：400，不写 Redis、不改账号")
    void rejectsWrongConfirmText() {
        assertThatThrownBy(() -> service.deactivate(USER_ID, "Password2026!", "CONFIRM"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
        verify(redisTemplate, never()).opsForValue();
        verify(mapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("密码不正确：401 AUTH_INVALID_CREDENTIALS，不写 Redis")
    void rejectsWrongPassword() {
        when(mapper.selectById(USER_ID)).thenReturn(account("ACTIVE"));
        when(passwordEncoder.matches("bad", "hash")).thenReturn(false);

        assertThatThrownBy(() -> service.deactivate(USER_ID, "bad", "CONFIRM_DEACTIVATE"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIALS));
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("注销成功：先写 Redis 阻断键，再更新账号并发布事件")
    void deactivatesAndPublishes() {
        when(mapper.selectById(USER_ID)).thenReturn(account("ACTIVE"));
        when(passwordEncoder.matches("Password2026!", "hash")).thenReturn(true);
        when(mapper.update(any(), any())).thenReturn(1);

        service.deactivate(USER_ID, "Password2026!", "CONFIRM_DEACTIVATE");

        verify(valueOps).set(eq(AccountDeactivationKey.of(USER_ID)), eq("1"),
                eq(AccountDeactivationKey.ttlFor(JWT_TTL_SECONDS)));
        verify(redisTemplate).delete(AccountDeactivationKey.activeMarker(USER_ID));
        verify(mapper).update(any(), any());
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(event.capture());
        assertThat(event.getValue()).isEqualTo(new UserDeactivatingEvent(USER_ID));
    }

    @Test
    @DisplayName("数据库事务回滚：撤销已写入的 Redis 阻断键，避免用户仍 ACTIVE 却被封禁")
    void rollsBackRedisBlockWhenTransactionFails() {
        when(mapper.selectById(USER_ID)).thenReturn(account("ACTIVE"));
        when(passwordEncoder.matches("Password2026!", "hash")).thenReturn(true);
        when(mapper.update(any(), any())).thenReturn(1);
        TransactionSynchronizationManager.initSynchronization();

        service.deactivate(USER_ID, "Password2026!", "CONFIRM_DEACTIVATE");

        var synchronizations = TransactionSynchronizationManager.getSynchronizations();
        assertThat(synchronizations).hasSize(1);
        synchronizations.get(0).afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(redisTemplate).delete(AccountDeactivationKey.of(USER_ID));
    }

    @Test
    @DisplayName("活跃缓存删除失败：不阻断注销，阻断键与业务状态仍生效")
    void activeMarkerDeleteFailureDoesNotAbortDeactivation() {
        when(mapper.selectById(USER_ID)).thenReturn(account("ACTIVE"));
        when(passwordEncoder.matches("Password2026!", "hash")).thenReturn(true);
        when(mapper.update(any(), any())).thenReturn(1);
        when(redisTemplate.delete(AccountDeactivationKey.activeMarker(USER_ID)))
                .thenThrow(new IllegalStateException("redis delete failed"));

        service.deactivate(USER_ID, "Password2026!", "CONFIRM_DEACTIVATE");

        verify(valueOps).set(eq(AccountDeactivationKey.of(USER_ID)), eq("1"),
                eq(AccountDeactivationKey.ttlFor(JWT_TTL_SECONDS)));
        verify(mapper).update(any(), any());
        verify(publisher).publishEvent(new UserDeactivatingEvent(USER_ID));
    }

    @Test
    @DisplayName("MySQL 更新失败：事务回滚撤销阻断键")
    void rollsBackRedisBlockWhenDatabaseUpdateFails() {
        when(mapper.selectById(USER_ID)).thenReturn(account("ACTIVE"));
        when(passwordEncoder.matches("Password2026!", "hash")).thenReturn(true);
        when(mapper.update(any(), any())).thenThrow(new IllegalStateException("db down"));
        TransactionSynchronizationManager.initSynchronization();

        assertThatThrownBy(() -> service.deactivate(USER_ID, "Password2026!",
                "CONFIRM_DEACTIVATE"))
                .isInstanceOf(IllegalStateException.class);

        TransactionSynchronizationManager.getSynchronizations().get(0)
                .afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(redisTemplate).delete(AccountDeactivationKey.of(USER_ID));
    }

    @Test
    @DisplayName("事件发布失败：事务回滚撤销阻断键")
    void rollsBackRedisBlockWhenEventPublishFails() {
        when(mapper.selectById(USER_ID)).thenReturn(account("ACTIVE"));
        when(passwordEncoder.matches("Password2026!", "hash")).thenReturn(true);
        when(mapper.update(any(), any())).thenReturn(1);
        org.mockito.Mockito.doThrow(new IllegalStateException("event failed"))
                .when(publisher).publishEvent(new UserDeactivatingEvent(USER_ID));
        TransactionSynchronizationManager.initSynchronization();

        assertThatThrownBy(() -> service.deactivate(USER_ID, "Password2026!",
                "CONFIRM_DEACTIVATE"))
                .isInstanceOf(IllegalStateException.class);

        TransactionSynchronizationManager.getSynchronizations().get(0)
                .afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(redisTemplate).delete(AccountDeactivationKey.of(USER_ID));
    }

    @Test
    @DisplayName("JWT TTL 超过 7 天：阻断键 TTL 按 JWT TTL + 1 天动态计算")
    void extendsDeactivatedTtlBeyondJwtLifetime() {
        long longJwtTtl = 30L * 24 * 3600;
        UserDeactivationService longLived = new UserDeactivationService(mapper,
                passwordEncoder, redisTemplate, publisher, longJwtTtl);
        when(mapper.selectById(USER_ID)).thenReturn(account("ACTIVE"));
        when(passwordEncoder.matches("Password2026!", "hash")).thenReturn(true);
        when(mapper.update(any(), any())).thenReturn(1);

        longLived.deactivate(USER_ID, "Password2026!", "CONFIRM_DEACTIVATE");

        verify(valueOps).set(eq(AccountDeactivationKey.of(USER_ID)), eq("1"),
                eq(Duration.ofSeconds(longJwtTtl + Duration.ofDays(1).toSeconds())));
    }

    @Test
    @DisplayName("已处于 DEACTIVATING：幂等返回，不重复发布事件")
    void idempotentWhenAlreadyDeactivating() {
        when(mapper.selectById(USER_ID)).thenReturn(account("DEACTIVATING"));
        when(passwordEncoder.matches("Password2026!", "hash")).thenReturn(true);

        service.deactivate(USER_ID, "Password2026!", "CONFIRM_DEACTIVATE");

        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("完成清理：Redis 清理成功后才物理删除 DEACTIVATING 用户行")
    @SuppressWarnings("unchecked")
    void completeCleanupDeletesRowAfterRedisPurge() {
        Cursor<String> cursor = mock(Cursor.class);
        doNothing().when(cursor).forEachRemaining(any());
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(mapper.delete(any())).thenReturn(1);

        service.completeCleanup(USER_ID);

        verify(mapper).delete(any());
    }

    @Test
    @DisplayName("Redis 清理失败：保留用户行，交给 GC 重试")
    @SuppressWarnings("unchecked")
    void keepsUserRowWhenRedisPurgeFails() {
        when(redisTemplate.scan(any(ScanOptions.class)))
                .thenThrow(new IllegalStateException("redis down"));

        service.completeCleanup(USER_ID);

        verify(mapper, never()).delete(any());
    }

    private static UserConfigEntity account(String status) {
        UserConfigEntity entity = new UserConfigEntity();
        entity.setUserId(USER_ID);
        entity.setAccountStatus(status);
        entity.setPasswordHash("hash");
        return entity;
    }
}
