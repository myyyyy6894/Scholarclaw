package com.scholarclaw.modules.course.listener;

import com.scholarclaw.common.event.CourseDeletingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 课程删除第二阶段的边界守卫（{@code 06} TASK-901、P9 复核第 2 项）。
 *
 * <p><b>为什么这里不再物理删除课程行：</b>课程行必须在
 * {@code KnowledgeDeletionService.finalizeCourseDeletion} 返回 true（向量已确认清理或补偿已落库、
 * raw 目录已删除、全部文档已置 {@code DELETED}）之后才能删除。事件提交时这些条件尚未满足；
 * 若在此提前删行，一旦 Python/Chroma 与 RabbitMQ 同时故障，残留文档会失去课程归属，
 * GC 再也找不到可靠入口。
 *
 * <p>因此本监听器只保留事件时序上的观测点，**物理删除统一由 {@code DeletionGcScheduler}
 * 在确认清理完成后执行**（{@code courseService.listStaleDeleting} →
 * {@code finalizeCourseDeletion} → {@code purgeSoftDeleted}）。
 */
@Component
public class CourseDeletedRowCleanupListener {

    private static final Logger log = LoggerFactory.getLogger(CourseDeletedRowCleanupListener.class);

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCourseDeleting(CourseDeletingEvent event) {
        log.debug("课程删除事件已提交，课程行保留 is_deleting=1 等待 GC 确认清理 userId={} "
                + "courseId={}", event.userId(), event.courseId());
    }
}
