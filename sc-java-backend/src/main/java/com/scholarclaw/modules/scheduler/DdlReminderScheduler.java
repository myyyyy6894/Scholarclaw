package com.scholarclaw.modules.scheduler;

import com.scholarclaw.common.filter.TraceContext;
import com.scholarclaw.modules.ddl.service.DdlReminderService;
import com.scholarclaw.modules.ddl.service.DdlReminderService.ReminderScanResult;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * DDL 定时提醒调度入口（TASK-801、{@code 02} §4.3 模块 9 / §6.3）。
 *
 * <p><b>职责边界：</b>本类只做定时触发、防重入锁与日志；提醒判定、免打扰换算与
 * {@code t_ddl_task} 的原子领取由 {@code ddl} 模块的公开 Service 完成。
 *
 * <p><b>时区：</b>{@code @Scheduled} 显式声明 {@code zone}（{@code ENV-01}）；
 * “当前时间”来自注入的 {@link Clock}（应用时区固定），不得使用 JVM 默认时区。
 *
 * <p><b>多实例并发：</b>优先获取 Redis {@code sc:lock:job:ddl_reminder}（TTL 默认 50s）。
 * 锁被其他实例持有时本轮直接跳过；Redis 不可用时**降级放行**并记录 WARN，
 * 因为同一梯度的最终防重由 {@code t_ddl_task} 的条件更新保证，不能因 Redis 故障让提醒整体停摆。
 *
 * <p><b>追踪：</b>每轮生成 {@code tr_cron_*} 并绑定 MDC，日志与后续通知记录可串联。
 */
@Component
public class DdlReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(DdlReminderScheduler.class);

    static final String LOCK_KEY = "sc:lock:job:ddl_reminder";

    /** 仅当锁值仍是本轮的 owner token 时才释放，避免 TTL 过期后误删其他实例的新锁。 */
    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] "
                    + "then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final DdlReminderService reminderService;
    private final StringRedisTemplate redisTemplate;
    private final Clock clock;
    private final boolean enabled;
    private final Duration lockTtl;

    public DdlReminderScheduler(DdlReminderService reminderService,
                                StringRedisTemplate redisTemplate,
                                Clock clock,
                                @Value("${sc.ddl.reminder.enabled:true}") boolean enabled,
                                @Value("${sc.ddl.reminder.lock-ttl-seconds:50}") long lockTtlSeconds) {
        this.reminderService = reminderService;
        this.redisTemplate = redisTemplate;
        this.clock = clock;
        this.enabled = enabled;
        this.lockTtl = Duration.ofSeconds(Math.max(1, lockTtlSeconds));
    }

    @Scheduled(initialDelayString = "${sc.ddl.reminder.initial-delay-ms:60000}",
            fixedDelayString = "${sc.ddl.reminder.interval-ms:60000}",
            zone = "${sc.timezone:Asia/Shanghai}")
    public void scheduledScan() {
        if (!enabled) {
            return;
        }
        try {
            runOnce();
        } catch (RuntimeException e) {
            // runOnce 已隔离业务异常；这里兜底，防止调度线程被未预期异常终止。
            log.error("DDL 提醒调度未预期失败 error={}", e.getClass().getSimpleName(), e);
        }
    }

    /**
     * 执行一次调度；返回 {@code null} 表示本轮被其他实例占用而跳过。
     *
     * <p>公开方法便于测试直接触发，不依赖真实 {@code @Scheduled} 时钟。
     */
    public ReminderScanResult runOnce() {
        String traceId = TraceContext.generateCronTraceId();
        TraceContext.bind(traceId);
        LockAttempt lock = null;
        try {
            lock = acquireLock();
            if (!lock.acquired()) {
                log.debug("DDL 提醒调度已被其他实例持有，本轮跳过");
                return null;
            }
            LocalDateTime now = LocalDateTime.now(clock);
            long startedAt = System.nanoTime();
            ReminderScanResult result = reminderService.scan(now, traceId);
            long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
            if (result.claimed() > 0 || result.failed() > 0) {
                log.info("DDL 提醒扫描完成 candidates={} claimed={} unclaimed={} failed={} elapsedMs={}",
                        result.candidates(), result.claimed(), result.unclaimed(),
                        result.failed(), elapsedMillis);
            } else {
                log.debug("DDL 提醒扫描完成 candidates={} claimed=0 elapsedMs={}",
                        result.candidates(), elapsedMillis);
            }
            if (elapsedMillis > lockTtl.toMillis()) {
                log.warn("DDL 提醒扫描耗时超过任务锁 TTL，多实例下可能重复扫描"
                        + "（DB 条件更新仍保证同一梯度只领取一次） elapsedMs={} lockTtlMs={}",
                        elapsedMillis, lockTtl.toMillis());
            }
            return result;
        } catch (RuntimeException e) {
            log.error("DDL 提醒调度失败 error={}", e.getClass().getSimpleName(), e);
            return null;
        } finally {
            if (lock != null) {
                releaseLock(lock.token());
            }
            TraceContext.clear();
        }
    }

    private LockAttempt acquireLock() {
        String token = UUID.randomUUID().toString();
        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(LOCK_KEY, token, lockTtl);
            return Boolean.TRUE.equals(acquired)
                    ? LockAttempt.acquired(token)
                    : LockAttempt.notAcquired();
        } catch (RuntimeException e) {
            // 失败放行：提醒位由数据库条件更新兜底，Redis 故障不应让全部提醒停摆。
            log.warn("DDL 提醒任务锁 Redis 不可用，降级为数据库条件更新防重 error={}",
                    e.getClass().getSimpleName());
            return LockAttempt.acquiredWithoutToken();
        }
    }

    private void releaseLock(String token) {
        if (token == null) {
            return;
        }
        try {
            Long released = redisTemplate.execute(RELEASE_LOCK_SCRIPT, List.of(LOCK_KEY), token);
            if (released == null || released == 0L) {
                log.debug("DDL 提醒任务锁已过期或被其他实例接管，未执行删除");
            }
        } catch (RuntimeException e) {
            // TTL 会在 50s 后自然释放；不把清理失败升级为业务失败。
            log.warn("DDL 提醒任务锁释放失败，等待 TTL 过期 error={}",
                    e.getClass().getSimpleName());
        }
    }

    private record LockAttempt(boolean acquired, String token) {

        static LockAttempt acquired(String token) {
            return new LockAttempt(true, token);
        }

        static LockAttempt acquiredWithoutToken() {
            return new LockAttempt(true, null);
        }

        static LockAttempt notAcquired() {
            return new LockAttempt(false, null);
        }
    }
}
