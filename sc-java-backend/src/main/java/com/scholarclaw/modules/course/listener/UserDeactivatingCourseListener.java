package com.scholarclaw.modules.course.listener;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.scholarclaw.common.config.ScTimeZone;
import com.scholarclaw.common.event.UserCleanupCompletedEvent;
import com.scholarclaw.common.event.UserDeactivatingEvent;
import com.scholarclaw.modules.course.entity.CourseNamespaceEntity;
import com.scholarclaw.modules.course.mapper.CourseNamespaceMapper;
import com.scholarclaw.modules.course.service.CourseDeletionReceiptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 账号注销时课程空间的冻结与物理删除（{@code 04} `API-USER-006`、TASK-901）。
 *
 * <p>第一阶段与账号状态更新同事务：全部课程置 `is_deleting = 1`，立即从列表与上传入口消失。
 * 第二阶段物理删除课程行；知识库的文件/向量/文档由 `knowledge` 模块按 `user_id` 独立清理。
 */
@Component
public class UserDeactivatingCourseListener {

    private static final Logger log = LoggerFactory.getLogger(
            UserDeactivatingCourseListener.class);

    private final CourseNamespaceMapper courseNamespaceMapper;
    private final CourseDeletionReceiptService receiptService;
    private final ApplicationEventPublisher eventPublisher;

    public UserDeactivatingCourseListener(CourseNamespaceMapper courseNamespaceMapper,
                                          CourseDeletionReceiptService receiptService,
                                          ApplicationEventPublisher eventPublisher) {
        this.courseNamespaceMapper = courseNamespaceMapper;
        this.receiptService = receiptService;
        this.eventPublisher = eventPublisher;
    }

    @EventListener
    public void freezeCourses(UserDeactivatingEvent event) {
        int updated = courseNamespaceMapper.update(null,
                Wrappers.<CourseNamespaceEntity>lambdaUpdate()
                        .eq(CourseNamespaceEntity::getUserId, event.userId())
                        .eq(CourseNamespaceEntity::getIsDeleting, 0)
                        .set(CourseNamespaceEntity::getIsDeleting, 1)
                        .set(CourseNamespaceEntity::getUpdatedAt,
                                ScTimeZone.now().toLocalDateTime()));
        log.info("账号注销：课程空间已冻结 userId={} rows={}", event.userId(), updated);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void purgeCourses(UserDeactivatingEvent event) {
        try {
            int deleted = courseNamespaceMapper.delete(
                    Wrappers.<CourseNamespaceEntity>lambdaQuery()
                            .eq(CourseNamespaceEntity::getUserId, event.userId()));
            int receipts = receiptService.deleteByUser(event.userId());
            log.info("账号注销：课程行物理删除完成 userId={} rows={} receipts={}",
                    event.userId(), deleted, receipts);
        } catch (RuntimeException e) {
            log.error("账号注销：课程行清理失败 userId={} error={}",
                    event.userId(), e.getClass().getSimpleName());
            return;
        }
        eventPublisher.publishEvent(new UserCleanupCompletedEvent(event.userId(),
                UserCleanupCompletedEvent.Module.COURSE));
    }
}
