package com.scholarclaw.modules.scheduler;

import com.scholarclaw.common.filter.TraceContext;
import com.scholarclaw.modules.course.service.CourseService;
import com.scholarclaw.modules.course.service.CourseService.StaleCourse;
import com.scholarclaw.modules.knowledge.entity.KnowledgeDocEntity;
import com.scholarclaw.modules.knowledge.service.KnowledgeDeletionService;
import com.scholarclaw.modules.knowledge.service.KnowledgeDocService;
import com.scholarclaw.modules.knowledge.service.OrphanResourceScanner;
import com.scholarclaw.modules.knowledge.service.OrphanResourceScanner.OrphanScanResult;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import com.scholarclaw.modules.userconfig.service.UserDeactivationService;
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
 * 删除残留与孤儿资源 GC（{@code 06} TASK-902、{@code 03} §6.7）。
 *
 * <p>每轮扫描并幂等重放（跨用户，按 `(status, updated_at)` / `(account_status, updated_at)` 索引）：
 * <ol>
 *   <li>`DEACTIVATING` 账号：重新发布注销事件，各模块重跑清理；</li>
 *   <li>`DELETING` 文档：向量 → 文件 → `DELETED`；</li>
 *   <li>`is_deleting=1` 课程：课程资源清理 + 物理删除课程行；</li>
 *   <li>raw 孤儿文件：数据库无有效文档行且超过阈值的文件。</li>
 * </ol>
 *
 * <p>只做定时触发与防重入锁；所有状态变更和删除都调用模块公开 Service，不直接写库。
 * 单个对象失败只记录 ERROR，继续处理其余对象；下一轮继续重试。
 */
@Component
public class DeletionGcScheduler {

    private static final Logger log = LoggerFactory.getLogger(DeletionGcScheduler.class);

    static final String LOCK_KEY = "sc:lock:job:deletion_gc";

    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] "
                    + "then redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final UserConfigService userConfigService;
    private final UserDeactivationService userDeactivationService;
    private final KnowledgeDocService docService;
    private final KnowledgeDeletionService deletionService;
    private final CourseService courseService;
    private final OrphanResourceScanner orphanScanner;
    private final StringRedisTemplate redisTemplate;
    private final Clock clock;
    private final boolean enabled;
    private final long staleMinutes;
    private final long orphanFileMinutes;
    private final int batchSize;
    private final Duration lockTtl;

    public DeletionGcScheduler(UserConfigService userConfigService,
                               UserDeactivationService userDeactivationService,
                               KnowledgeDocService docService,
                               KnowledgeDeletionService deletionService,
                               CourseService courseService,
                               OrphanResourceScanner orphanScanner,
                               StringRedisTemplate redisTemplate,
                               Clock clock,
                               @Value("${sc.deletion-gc.enabled:true}") boolean enabled,
                               @Value("${sc.deletion-gc.stale-minutes:10}") long staleMinutes,
                               @Value("${sc.deletion-gc.orphan-file-minutes:60}")
                               long orphanFileMinutes,
                               @Value("${sc.deletion-gc.batch-size:200}") int batchSize,
                               @Value("${sc.deletion-gc.lock-ttl-seconds:300}")
                               long lockTtlSeconds) {
        this.userConfigService = userConfigService;
        this.userDeactivationService = userDeactivationService;
        this.docService = docService;
        this.deletionService = deletionService;
        this.courseService = courseService;
        this.orphanScanner = orphanScanner;
        this.redisTemplate = redisTemplate;
        this.clock = clock;
        this.enabled = enabled;
        this.staleMinutes = Math.max(1, staleMinutes);
        this.orphanFileMinutes = Math.max(1, orphanFileMinutes);
        this.batchSize = Math.max(1, batchSize);
        this.lockTtl = Duration.ofSeconds(Math.max(1, lockTtlSeconds));
    }

    @Scheduled(initialDelayString = "${sc.deletion-gc.initial-delay-ms:60000}",
            fixedDelayString = "${sc.deletion-gc.interval-ms:3600000}",
            zone = "${sc.timezone:Asia/Shanghai}")
    public void scheduledScan() {
        if (!enabled) {
            return;
        }
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.error("删除 GC 调度未预期失败 error={}", e.getClass().getSimpleName(), e);
        }
    }

    /** 执行一轮 GC；返回统计；被其他实例持锁时返回 {@code null}。 */
    public GcResult runOnce() {
        String traceId = TraceContext.generateCronTraceId();
        TraceContext.bind(traceId);
        LockAttempt lock = null;
        try {
            lock = acquireLock();
            if (!lock.acquired()) {
                log.debug("删除 GC 已被其他实例持有，本轮跳过");
                return null;
            }
            LocalDateTime threshold = LocalDateTime.now(clock)
                    .minusMinutes(staleMinutes);
            int users = purgeUsers(threshold);
            int docs = purgeDocuments(threshold);
            int courses = purgeCourses(threshold);
            OrphanScanResult orphans = orphanScanner.scanOrphanRawFiles(
                    Duration.ofMinutes(orphanFileMinutes), batchSize);
            GcResult result = new GcResult(users, docs, courses, orphans.scanned(),
                    orphans.deleted(), orphans.failed());
            if (users + docs + courses + orphans.deleted() + orphans.failed() > 0) {
                log.info("删除 GC 完成 users={} docs={} courses={} orphanScanned={} "
                                + "orphanDeleted={} orphanFailed={}",
                        users, docs, courses, orphans.scanned(), orphans.deleted(),
                        orphans.failed());
            } else {
                log.debug("删除 GC 完成：无滞留资源");
            }
            return result;
        } finally {
            if (lock != null) {
                releaseLock(lock.token());
            }
            TraceContext.clear();
        }
    }

    private int purgeUsers(LocalDateTime threshold) {
        int cleaned = 0;
        List<Long> users = userConfigService.findStaleDeactivating(threshold, batchSize);
        for (Long userId : users) {
            try {
                userDeactivationService.retryCleanup(userId);
                cleaned++;
            } catch (RuntimeException e) {
                log.error("删除 GC：注销用户重放失败 userId={} error={}",
                        userId, e.getClass().getSimpleName());
            }
        }
        return cleaned;
    }

    private int purgeDocuments(LocalDateTime threshold) {
        int cleaned = 0;
        List<KnowledgeDocEntity> docs = docService.listStaleDeleting(threshold, batchSize);
        for (KnowledgeDocEntity doc : docs) {
            try {
                if (deletionService.finalizeDocumentDeletion(doc.getUserId(), doc, null)) {
                    cleaned++;
                }
            } catch (RuntimeException e) {
                log.error("删除 GC：文档清理失败 docId={} error={}",
                        doc.getDocId(), e.getClass().getSimpleName());
            }
        }
        return cleaned;
    }

    private int purgeCourses(LocalDateTime threshold) {
        int cleaned = 0;
        List<StaleCourse> courses = courseService.listStaleDeleting(threshold, batchSize);
        for (StaleCourse course : courses) {
            try {
                deletionService.finalizeCourseDeletion(course.userId(), course.courseId(),
                        course.indexVersion());
                courseService.purgeSoftDeleted(course.userId(), course.courseId());
                cleaned++;
            } catch (RuntimeException e) {
                log.error("删除 GC：课程清理失败 courseId={} error={}",
                        course.courseId(), e.getClass().getSimpleName());
            }
        }
        return cleaned;
    }

    private LockAttempt acquireLock() {
        String token = UUID.randomUUID().toString();
        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(LOCK_KEY, token, lockTtl);
            return new LockAttempt(Boolean.TRUE.equals(acquired), token);
        } catch (RuntimeException e) {
            // 与 DDL 调度一致：Redis 故障降级放行，靠各清理接口的幂等性兜底。
            log.warn("删除 GC 任务锁不可用，降级放行 error={}",
                    e.getClass().getSimpleName());
            return new LockAttempt(true, token);
        }
    }

    private void releaseLock(String token) {
        try {
            redisTemplate.execute(RELEASE_LOCK_SCRIPT, List.of(LOCK_KEY), token);
        } catch (RuntimeException e) {
            log.debug("删除 GC 任务锁释放失败 error={}", e.getClass().getSimpleName());
        }
    }

    private record LockAttempt(boolean acquired, String token) {
    }

    /** 单轮统计。 */
    public record GcResult(int users, int documents, int courses,
                           int orphanScanned, int orphanDeleted, int orphanFailed) {
    }
}
