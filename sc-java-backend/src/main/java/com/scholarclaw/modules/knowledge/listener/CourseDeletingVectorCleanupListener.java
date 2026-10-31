package com.scholarclaw.modules.knowledge.listener;

import com.scholarclaw.common.event.CourseDeletingEvent;
import com.scholarclaw.modules.knowledge.service.KnowledgeDeletionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 课程删除事务提交后的第二阶段资源清理（TASK-506 引入，TASK-901 扩展为完整清理）。
 *
 * <p>{@code 04} §4.3.4 要求删除课程时直接删除 `sc_u{uid}_c{cid}_v*`，并在第二阶段删除课程
 * raw 目录、把文档置为 `DELETED`。清理失败时保留 `DELETING` 文档行，由 TASK-902 的 GC 重试；
 * 向量失败另由 {@link com.scholarclaw.modules.knowledge.service.KnowledgeVectorCleanupService}
 * 写入持久化补偿队列。课程行本身由 `course` 模块自行物理删除。
 */
@Component
public class CourseDeletingVectorCleanupListener {

    private static final Logger log = LoggerFactory.getLogger(
            CourseDeletingVectorCleanupListener.class);

    private final KnowledgeDeletionService deletionService;

    public CourseDeletingVectorCleanupListener(KnowledgeDeletionService deletionService) {
        this.deletionService = deletionService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCourseDeleting(CourseDeletingEvent event) {
        boolean cleaned = deletionService.finalizeCourseDeletion(event.userId(),
                event.courseId(), null);
        if (!cleaned) {
            log.warn("课程资源清理未完成，保留 DELETING 等待 GC courseId={}", event.courseId());
        }
    }
}
