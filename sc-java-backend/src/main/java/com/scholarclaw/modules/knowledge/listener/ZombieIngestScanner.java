package com.scholarclaw.modules.knowledge.listener;

import com.scholarclaw.common.config.ScTimeZone;
import com.scholarclaw.modules.knowledge.entity.KnowledgeDocEntity;
import com.scholarclaw.modules.knowledge.service.KnowledgeDocService;
import com.scholarclaw.modules.knowledge.service.KnowledgeIngestService;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 僵尸解析任务补投（{@code 04} §4.4.2：MQ 闪断时文档留在 `UPLOADED`）。
 *
 * <p>只扫描已落盘、从未成功入队（`enqueued_at IS NULL`）且超过阈值的 `UPLOADED` 文档；
 * 补投成功后由 {@link KnowledgeIngestService} 推进为 `PARSING`。合法等待的 `UPLOADED`
 * 不会重复投递，真正的去重仍由消费者的 `parse_version` 校验兜底。
 */
@Component
public class ZombieIngestScanner {

    private static final Logger log = LoggerFactory.getLogger(ZombieIngestScanner.class);

    private final KnowledgeDocService docService;
    private final KnowledgeIngestService ingestService;
    private final boolean enabled;
    private final long staleMinutes;
    private final int batchSize;

    public ZombieIngestScanner(
            KnowledgeDocService docService,
            KnowledgeIngestService ingestService,
            @Value("${sc.knowledge.zombie-scan.enabled:true}") boolean enabled,
            @Value("${sc.knowledge.zombie-scan.stale-minutes:5}") long staleMinutes,
            @Value("${sc.knowledge.zombie-scan.batch-size:50}") int batchSize) {
        this.docService = docService;
        this.ingestService = ingestService;
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
            int published = scan();
            if (published > 0) {
                log.info("僵尸解析任务补投完成 published={}", published);
            }
        } catch (RuntimeException e) {
            log.error("僵尸解析任务扫描失败 error={}", e.getClass().getSimpleName(), e);
        }
    }

    /** 执行一次补投；返回成功入队数量。 */
    public int scan() {
        LocalDateTime before = ScTimeZone.now().toLocalDateTime().minusMinutes(staleMinutes);
        List<KnowledgeDocEntity> stale = docService.findStaleUploaded(before, batchSize);
        int published = 0;
        for (KnowledgeDocEntity doc : stale) {
            if (ingestService.enqueueInitial(doc.getUserId(), doc.getDocId())) {
                published++;
            }
        }
        return published;
    }
}
