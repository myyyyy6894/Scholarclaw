package com.scholarclaw.modules.knowledge.listener;

import com.scholarclaw.common.config.ScTimeZone;
import com.scholarclaw.modules.knowledge.entity.KnowledgeDocEntity;
import com.scholarclaw.modules.knowledge.service.KnowledgeDocService;
import com.scholarclaw.modules.knowledge.service.KnowledgeFailureService;
import com.scholarclaw.modules.knowledge.service.KnowledgeIngestService;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 僵尸解析任务巡检（{@code 01} §3.3.2、{@code 02} §8.5、TASK-506 复核修复）。
 *
 * <p>扫描 `UPLOADED/PARSING` 且超过阈值、`retry_count < 3` 的文档，先原子递增
 * `parse_version/retry_count` 再重新发布；达到 3 次仍滞留的文档收敛为 `FAILED` 并清理
 * 父块与向量。发布未获 broker 确认时保持 `PARSING + enqueued_at=NULL`，下一轮继续补投。
 */
@Component
public class ZombieIngestScanner {

    private static final Logger log = LoggerFactory.getLogger(ZombieIngestScanner.class);

    private final KnowledgeDocService docService;
    private final KnowledgeIngestService ingestService;
    private final KnowledgeFailureService failureService;
    private final boolean enabled;
    private final long staleMinutes;
    private final int batchSize;

    public ZombieIngestScanner(
            KnowledgeDocService docService,
            KnowledgeIngestService ingestService,
            KnowledgeFailureService failureService,
            @Value("${sc.knowledge.zombie-scan.enabled:true}") boolean enabled,
            @Value("${sc.knowledge.zombie-scan.stale-minutes:15}") long staleMinutes,
            @Value("${sc.knowledge.zombie-scan.batch-size:50}") int batchSize) {
        this.docService = docService;
        this.ingestService = ingestService;
        this.failureService = failureService;
        this.enabled = enabled;
        this.staleMinutes = staleMinutes;
        this.batchSize = batchSize;
    }

    @Scheduled(initialDelayString = "${sc.knowledge.zombie-scan.initial-delay-ms:60000}",
            fixedDelayString = "${sc.knowledge.zombie-scan.interval-ms:300000}")
    public void scheduledScan() {
        if (!enabled) {
            return;
        }
        try {
            int handled = scan();
            if (handled > 0) {
                log.info("僵尸解析任务巡检完成 handled={}", handled);
            }
        } catch (RuntimeException e) {
            log.error("僵尸解析任务扫描失败 error={}", e.getClass().getSimpleName(), e);
        }
    }

    /** 执行一次巡检；返回成功补投或收敛为失败的数量。 */
    public int scan() {
        LocalDateTime before = ScTimeZone.now().toLocalDateTime().minusMinutes(staleMinutes);
        int handled = 0;
        for (KnowledgeDocEntity doc : docService.findRetryExhausted(before, batchSize)) {
            if (failureService.fail(doc.getUserId(), doc.getCourseId(), doc.getDocId(),
                    doc.getParseVersion(), doc.getCourseIndexVersion(), "E-RAG-02",
                    "课件解析多次重试后仍失败，请稍后手动重试或更换文件。").applied()) {
                handled++;
            }
        }
        List<KnowledgeDocEntity> stale = docService.findStaleTasks(before, batchSize);
        for (KnowledgeDocEntity doc : stale) {
            if (ingestService.republishStale(doc, before)) {
                handled++;
            }
        }
        return handled;
    }
}
