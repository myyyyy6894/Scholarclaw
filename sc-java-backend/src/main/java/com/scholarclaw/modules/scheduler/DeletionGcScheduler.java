package com.scholarclaw.modules.scheduler;

import com.scholarclaw.common.filter.TraceContext;
import com.scholarclaw.modules.course.service.CourseService;
import com.scholarclaw.modules.course.service.CourseService.StaleCourse;
import com.scholarclaw.modules.knowledge.entity.KnowledgeDocEntity;
import com.scholarclaw.modules.knowledge.entity.VectorCleanupOutboxEntity;
import com.scholarclaw.modules.knowledge.service.ChromaReconciliationScanner;
import com.scholarclaw.modules.knowledge.service.KnowledgeDeletionService;
import com.scholarclaw.modules.knowledge.service.KnowledgeDocService;
import com.scholarclaw.modules.knowledge.service.KnowledgeVectorCleanupService;
import com.scholarclaw.modules.knowledge.service.OrphanResourceScanner;
import com.scholarclaw.modules.knowledge.service.OrphanResourceScanner.OrphanScanResult;
import com.scholarclaw.modules.knowledge.service.VectorCleanupOutboxService;
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
    private final VectorCleanupOutboxService outboxService;
    private final KnowledgeVectorCleanupService vectorCleanupService;
    private final ChromaReconciliationScanner chromaScanner;
    private final StringRedisTemplate redisTemplate;
    private final Clock clock;
    private final boolean enabled;
    private final long staleMinutes;
    private final long orphanFileMinutes;
    private final int batchSize;
    private final Duration lockTtl;
    private final boolean chromaReconcileEnabled;
    private final Duration outboxLease;

    public DeletionGcScheduler(UserConfigService userConfigService,
                               UserDeactivationService userDeactivationService,
                               KnowledgeDocService docService,
                               KnowledgeDeletionService deletionService,
                               CourseService courseService,
                               OrphanResourceScanner orphanScanner,
                               VectorCleanupOutboxService outboxService,
                               KnowledgeVectorCleanupService vectorCleanupService,
                               ChromaReconciliationScanner chromaScanner,
                               StringRedisTemplate redisTemplate,
                               Clock clock,
                               @Value("${sc.deletion-gc.enabled:true}") boolean enabled,
                               @Value("${sc.deletion-gc.stale-minutes:10}") long staleMinutes,
                               @Value("${sc.deletion-gc.orphan-file-minutes:60}")
                               long orphanFileMinutes,
                               @Value("${sc.deletion-gc.batch-size:200}") int batchSize,
                               @Value("${sc.deletion-gc.lock-ttl-seconds:300}")
                               long lockTtlSeconds,
                               @Value("${sc.deletion-gc.chroma-reconcile-enabled:true}")
                               boolean chromaReconcileEnabled,
                               @Value("${sc.deletion-gc.outbox-lease-seconds:120}")
                               long outboxLeaseSeconds) {
        this.userConfigService = userConfigService;
        this.userDeactivationService = userDeactivationService;
        this.docService = docService;
        this.deletionService = deletionService;
        this.courseService = courseService;
        this.orphanScanner = orphanScanner;
        this.outboxService = outboxService;
        this.vectorCleanupService = vectorCleanupService;
        this.chromaScanner = chromaScanner;
        this.redisTemplate = redisTemplate;
        this.clock = clock;
        this.enabled = enabled;
        this.staleMinutes = Math.max(1, staleMinutes);
        this.orphanFileMinutes = Math.max(1, orphanFileMinutes);
        this.batchSize = Math.max(1, batchSize);
        this.lockTtl = Duration.ofSeconds(Math.max(1, lockTtlSeconds));
        this.chromaReconcileEnabled = chromaReconcileEnabled;
        this.outboxLease = Duration.ofSeconds(Math.max(30, outboxLeaseSeconds));
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
            LocalDateTime now = LocalDateTime.now(clock);
            LocalDateTime threshold = now.minusMinutes(staleMinutes);
            int users = purgeUsers(threshold);
            int docs = purgeDocuments(threshold);
            int courses = purgeCourses(threshold);
            int outbox = purgeVectorOutbox(now);
            int manual = escalateStaleOutbox(now);
            OrphanScanResult orphans = orphanScanner.scanOrphanRawFiles(
                    Duration.ofMinutes(orphanFileMinutes), batchSize);
            ChromaReconciliationScanner.ScanResult chroma = chromaReconcileEnabled
                    ? chromaScanner.reconcile(batchSize)
                    : new ChromaReconciliationScanner.ScanResult(0, 0, 0);
            GcResult result = new GcResult(users, docs, courses, orphans.scanned(),
                    orphans.deleted(), orphans.failed(), outbox, manual,
                    chroma.scanned(), chroma.deleted(), chroma.failed());
            if (users + docs + courses + outbox + manual + orphans.deleted()
                    + orphans.failed() + chroma.deleted() + chroma.failed() > 0) {
                log.info("删除 GC 完成 users={} docs={} courses={} vectorOutbox={} "
                                + "outboxManual={} orphanScanned={} orphanDeleted={} "
                                + "orphanFailed={} chromaScanned={} chromaDeleted={} "
                                + "chromaFailed={}",
                        users, docs, courses, outbox, manual, orphans.scanned(),
                        orphans.deleted(), orphans.failed(), chroma.scanned(),
                        chroma.deleted(), chroma.failed());
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
                boolean cleared = deletionService.finalizeCourseDeletion(course.userId(),
                        course.courseId(), course.indexVersion());
                if (!cleared) {
                    // 课程行必须等向量/文件/文档清理完成后才能物理删除；否则残留文档
                    // 会失去 course 归属，ChromaDB 也再没有可靠入口可重试。
                    log.warn("删除 GC：课程资源未清理完成，保留课程行 courseId={}",
                            course.courseId());
                    continue;
                }
                courseService.purgeSoftDeleted(course.userId(), course.courseId());
                cleaned++;
            } catch (RuntimeException e) {
                log.error("删除 GC：课程清理失败 courseId={} error={}",
                        course.courseId(), e.getClass().getSimpleName());
            }
        }
        return cleaned;
    }

    /** 重放持久化向量清理 outbox；返回本轮完成数。 */
    private int purgeVectorOutbox(LocalDateTime now) {
        int cleaned = 0;
        List<VectorCleanupOutboxEntity> tasks = outboxService.findDue(now, batchSize);
        for (VectorCleanupOutboxEntity task : tasks) {
            if (!outboxService.claim(task.getCleanupId(), now, outboxLease)) {
                continue;
            }
            boolean ok;
            try {
                ok = vectorCleanupService.cleanupDirect(task.getScope(), task.getUserId(),
                        task.getCourseId(), task.getDocId(),
                        task.getCourseIndexVersion(), task.getStaleParseVersion());
            } catch (RuntimeException e) {
                ok = false;
                log.error("删除 GC：向量补偿重放异常 cleanupId={} scope={} error={}",
                        task.getCleanupId(), task.getScope(), e.getClass().getSimpleName());
            }
            if (ok) {
                outboxService.markDone(task.getCleanupId());
                cleaned++;
            } else {
                int retryCount = (task.getRetryCount() == null ? 0 : task.getRetryCount()) + 1;
                outboxService.markRetry(task.getCleanupId(), retryCount,
                        "向量清理重放失败", now.plus(retryBackoff(retryCount)));
                log.warn("删除 GC：向量补偿重放失败，已安排重试 cleanupId={} scope={} retry={}",
                        task.getCleanupId(), task.getScope(), retryCount);
            }
        }
        return cleaned;
    }

    /**
     * 超过 24 小时仍未完成的补偿升级为 `MANUAL`，并输出人工介入告警。
     *
     * @return 本轮升级为人工处理的数量
     */
    private int escalateStaleOutbox(LocalDateTime now) {
        int manual = outboxService.markStaleManual(now.minusHours(24));
        if (manual > 0) {
            log.error("[GC_MANUAL_INTERVENTION_REQUIRED] 向量清理补偿超过 24 小时未完成，"
                    + "已标记 MANUAL count={}，请检查 sc-python-ai / ChromaDB 并按 trace_id 人工重放",
                    manual);
        }
        return manual;
    }

    static Duration retryBackoff(int retryCount) {
        if (retryCount <= 1) {
            return Duration.ofMinutes(1);
        }
        if (retryCount == 2) {
            return Duration.ofMinutes(5);
        }
        if (retryCount == 3) {
            return Duration.ofMinutes(15);
        }
        if (retryCount == 4) {
            return Duration.ofHours(1);
        }
        return Duration.ofHours(6);
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
                           int orphanScanned, int orphanDeleted, int orphanFailed,
                           int vectorOutboxCleaned, int outboxManual,
                           int chromaScanned, int chromaDeleted, int chromaFailed) {
    }
}
