package com.scholarclaw.modules.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scholarclaw.common.filter.TraceContext;
import com.scholarclaw.modules.ddl.service.DdlReminderService;
import com.scholarclaw.modules.ddl.service.DdlReminderService.ReminderScanResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.annotation.Scheduled;

/** TASK-801 定时入口的时区时钟、Redis 任务锁与失败降级。 */
class DdlReminderSchedulerTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 28, 10, 0);
    private static final Clock CLOCK = Clock.fixed(
            NOW.atZone(ZONE).toInstant(), ZONE);

    private DdlReminderService reminderService;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private DdlReminderScheduler scheduler;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        reminderService = mock(DdlReminderService.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        scheduler = new DdlReminderScheduler(reminderService, redisTemplate, CLOCK, true, 50);
    }

    @AfterEach
    void clearTrace() {
        TraceContext.clear();
    }

    @Test
    @DisplayName("获取锁成功：绑定 tr_cron_* 追踪号、使用应用时区时钟并释放锁")
    void runsWithCronTraceAndReleasesLock() {
        when(valueOperations.setIfAbsent(eq(DdlReminderScheduler.LOCK_KEY),
                anyString(), eq(Duration.ofSeconds(50)))).thenReturn(true);
        ReminderScanResult expected = new ReminderScanResult(2, 1, 1, 0, List.of());
        when(reminderService.scan(eq(NOW), anyString())).thenReturn(expected);
        doReturn(1L).when(redisTemplate).execute(any(), anyList(), any());

        ReminderScanResult result = scheduler.runOnce();

        assertThat(result).isSameAs(expected);
        ArgumentCaptor<String> trace = ArgumentCaptor.forClass(String.class);
        verify(reminderService).scan(eq(NOW), trace.capture());
        assertThat(trace.getValue()).matches("^tr_cron_[0-9a-f]{12}$");
        verify(redisTemplate).execute(any(), eq(List.of(DdlReminderScheduler.LOCK_KEY)),
                anyString());
        assertThat(TraceContext.currentTraceId()).isNull();
    }

    @Test
    @DisplayName("锁被其他实例持有时本轮跳过，不扫描任务")
    void skipsWhenLockHeldByAnotherInstance() {
        when(valueOperations.setIfAbsent(eq(DdlReminderScheduler.LOCK_KEY),
                anyString(), eq(Duration.ofSeconds(50)))).thenReturn(false);

        assertThat(scheduler.runOnce()).isNull();

        verify(reminderService, never()).scan(any(), anyString());
    }

    @Test
    @DisplayName("Redis 不可用时降级放行，仍执行数据库兜底扫描")
    void degradesOpenWhenRedisUnavailable() {
        when(valueOperations.setIfAbsent(eq(DdlReminderScheduler.LOCK_KEY),
                anyString(), eq(Duration.ofSeconds(50))))
                .thenThrow(new IllegalStateException("redis down"));
        ReminderScanResult expected = new ReminderScanResult(1, 1, 0, 0, List.of());
        when(reminderService.scan(eq(NOW), anyString())).thenReturn(expected);

        assertThat(scheduler.runOnce()).isSameAs(expected);
        verify(reminderService).scan(eq(NOW), anyString());
        // Redis 故障时没有 owner token，不得尝试删除别的锁。
        verify(redisTemplate, never()).execute(any(), anyList(), any());
    }

    @Test
    @DisplayName("释放锁失败不影响本轮结果，等待 TTL 过期")
    void releaseFailureDoesNotFailScan() {
        when(valueOperations.setIfAbsent(eq(DdlReminderScheduler.LOCK_KEY),
                anyString(), eq(Duration.ofSeconds(50)))).thenReturn(true);
        ReminderScanResult expected = new ReminderScanResult(1, 1, 0, 0, List.of());
        when(reminderService.scan(eq(NOW), anyString())).thenReturn(expected);
        doThrow(new IllegalStateException("redis down"))
                .when(redisTemplate).execute(any(), anyList(), any());

        assertThat(scheduler.runOnce()).isSameAs(expected);
    }

    @Test
    @DisplayName("扫描抛异常时记录错误并返回 null，追踪上下文仍被清理")
    void scanFailureIsIsolatedAndTraceCleared() {
        when(valueOperations.setIfAbsent(eq(DdlReminderScheduler.LOCK_KEY),
                anyString(), eq(Duration.ofSeconds(50)))).thenReturn(true);
        when(reminderService.scan(eq(NOW), anyString()))
                .thenThrow(new IllegalStateException("db down"));
        doReturn(1L).when(redisTemplate).execute(any(), anyList(), any());

        assertThat(scheduler.runOnce()).isNull();
        assertThat(TraceContext.currentTraceId()).isNull();
    }

    @Test
    @DisplayName("开关关闭时不扫描、不访问 Redis")
    void disabledSchedulerDoesNothing() {
        DdlReminderScheduler disabled = new DdlReminderScheduler(
                reminderService, redisTemplate, CLOCK, false, 50);

        disabled.scheduledScan();

        verify(reminderService, never()).scan(any(), anyString());
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    @DisplayName("开关开启时定时入口执行一次扫描")
    void enabledSchedulerTriggersScan() {
        when(valueOperations.setIfAbsent(eq(DdlReminderScheduler.LOCK_KEY),
                anyString(), eq(Duration.ofSeconds(50)))).thenReturn(true);
        when(reminderService.scan(eq(NOW), anyString()))
                .thenReturn(new ReminderScanResult(0, 0, 0, 0, List.of()));
        doReturn(1L).when(redisTemplate).execute(any(), anyList(), any());

        scheduler.scheduledScan();

        verify(reminderService).scan(eq(NOW), anyString());
    }

    @Test
    @DisplayName("调度注解显式声明时区与可配置间隔（ENV-01 硬约束）")
    void scheduledAnnotationDeclaresExplicitZone() throws Exception {
        Scheduled scheduled = DdlReminderScheduler.class
                .getMethod("scheduledScan")
                .getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.zone()).isEqualTo("${sc.timezone:Asia/Shanghai}");
        assertThat(scheduled.fixedDelayString()).contains("sc.ddl.reminder.interval-ms");
        assertThat(scheduled.initialDelayString()).contains("sc.ddl.reminder.initial-delay-ms");
    }

    @Test
    @DisplayName("调度时钟严格来自注入 Clock（应用时区），不使用系统默认时区")
    void usesInjectedClock() {
        Instant instant = Instant.parse("2026-10-28T02:00:00Z");
        Clock fixed = Clock.fixed(instant, ZONE);
        DdlReminderScheduler custom = new DdlReminderScheduler(
                reminderService, redisTemplate, fixed, true, 50);
        when(valueOperations.setIfAbsent(eq(DdlReminderScheduler.LOCK_KEY),
                anyString(), eq(Duration.ofSeconds(50)))).thenReturn(true);
        when(reminderService.scan(eq(LocalDateTime.of(2026, 10, 28, 10, 0)), anyString()))
                .thenReturn(new ReminderScanResult(0, 0, 0, 0, List.of()));
        doReturn(1L).when(redisTemplate).execute(any(), anyList(), any());

        custom.runOnce();

        verify(reminderService).scan(eq(LocalDateTime.of(2026, 10, 28, 10, 0)), anyString());
    }
}
