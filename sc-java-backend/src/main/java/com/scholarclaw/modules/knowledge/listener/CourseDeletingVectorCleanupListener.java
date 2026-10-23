package com.scholarclaw.modules.knowledge.listener;

import com.scholarclaw.common.event.CourseDeletingEvent;
import com.scholarclaw.modules.knowledge.service.KnowledgeVectorCleanupService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 课程删除事务提交后清理整门课程的 ChromaDB Collections（TASK-506 复核修复）。
 *
 * <p>{@code 04} §4.3.4 要求删除课程时直接删除 `sc_u{uid}_c{cid}_v*`，不能只把文档标为
 * `DELETING` 后把向量留给后续 GC。重试与持久化补偿统一由
 * {@link KnowledgeVectorCleanupService} 负责；失败的软删课程行保留为补偿标记。
 */
@Component
public class CourseDeletingVectorCleanupListener {

    private final KnowledgeVectorCleanupService cleanupService;

    public CourseDeletingVectorCleanupListener(KnowledgeVectorCleanupService cleanupService) {
        this.cleanupService = cleanupService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCourseDeleting(CourseDeletingEvent event) {
        cleanupService.cleanupCourse(event.userId(), event.courseId(), null);
    }
}
