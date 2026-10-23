package com.scholarclaw.modules.knowledge.listener;

import com.scholarclaw.common.event.CourseEmbeddingChangedEvent;
import com.scholarclaw.modules.knowledge.service.KnowledgeIngestService;
import com.scholarclaw.modules.knowledge.service.KnowledgeVectorCleanupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 课程切换事务提交后批量发布 `REINDEX`（TASK-501）。
 *
 * <p>状态迁移与父块清空已由 {@link CourseEmbeddingChangedListener} 在同一事务内完成；
 * 本监听器只负责 `AFTER_COMMIT` 的 MQ 投递，避免消息先于数据库提交到达消费者。
 */
@Component
public class CourseEmbeddingChangedMqListener {

    private static final Logger log = LoggerFactory.getLogger(CourseEmbeddingChangedMqListener.class);

    private final KnowledgeIngestService ingestService;
    private final KnowledgeVectorCleanupService vectorCleanupService;

    public CourseEmbeddingChangedMqListener(KnowledgeIngestService ingestService,
                                            KnowledgeVectorCleanupService vectorCleanupService) {
        this.ingestService = ingestService;
        this.vectorCleanupService = vectorCleanupService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCourseEmbeddingChanged(CourseEmbeddingChangedEvent event) {
        int published = ingestService.publishReindexForCourse(
                event.userId(), event.courseId(), event.currentIndexVersion());
        // 旧 Collection 不再被检索使用；清理服务立即执行，瞬态失败重试一次并写补偿队列，GC 仅最终兜底
        vectorCleanupService.cleanupCourse(event.userId(), event.courseId(),
                event.previousIndexVersion());
        log.info("课程切换后发布 REINDEX courseId={} version={} published={}",
                event.courseId(), event.currentIndexVersion(), published);
    }
}
