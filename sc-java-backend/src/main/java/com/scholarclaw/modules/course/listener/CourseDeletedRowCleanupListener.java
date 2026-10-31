package com.scholarclaw.modules.course.listener;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.scholarclaw.common.event.CourseDeletingEvent;
import com.scholarclaw.modules.course.entity.CourseNamespaceEntity;
import com.scholarclaw.modules.course.mapper.CourseNamespaceMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 课程删除第二阶段：物理删除 `t_course_namespace` 行（{@code 06} TASK-901）。
 *
 * <p>第一阶段已把 `is_deleting` 置 1 并同步冻结文档/清空父块/解绑任务；事务提交后由
 * `course` 模块（该表的唯一写入者）物理删除课程行。`knowledge` 模块的第二阶段清理只依赖
 * 课程 ID 与文档行，不依赖该行是否存在；若文档/文件清理失败，文档保留 `DELETING`，
 * 由 TASK-902 的 GC 按文档行继续重试。
 */
@Component
public class CourseDeletedRowCleanupListener {

    private static final Logger log = LoggerFactory.getLogger(CourseDeletedRowCleanupListener.class);

    private final CourseNamespaceMapper courseNamespaceMapper;

    public CourseDeletedRowCleanupListener(CourseNamespaceMapper courseNamespaceMapper) {
        this.courseNamespaceMapper = courseNamespaceMapper;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void onCourseDeleting(CourseDeletingEvent event) {
        int deleted = courseNamespaceMapper.delete(Wrappers.<CourseNamespaceEntity>lambdaQuery()
                .eq(CourseNamespaceEntity::getCourseId, event.courseId())
                .eq(CourseNamespaceEntity::getUserId, event.userId())
                .eq(CourseNamespaceEntity::getIsDeleting, 1));
        log.info("课程行物理删除完成 userId={} courseId={} rows={}",
                event.userId(), event.courseId(), deleted);
    }
}
