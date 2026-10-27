package com.scholarclaw.modules.ddl.listener;

import com.scholarclaw.common.event.CourseDeletingEvent;
import com.scholarclaw.modules.ddl.mapper.DdlTaskMapper;
import com.scholarclaw.modules.ddl.service.DdlDraftStore;
import java.util.List;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 课程删除时解绑 DDL 任务（{@code 04} `API-COURSE-004`、`PO-05`）。
 *
 * <p>监听 `course` 模块在第一阶段软删事务内发布的 {@link CourseDeletingEvent}，在**同一数据库事务**内
 * 执行 {@code course_id = NULL}。`ddl` 是 {@code t_ddl_task} 的唯一写入者，因此不在
 * `course` 模块直接改表（Strict DAG + Single-Writer）。
 *
 * <p>同时清理该课程下任务在 Redis 中的变更草案：否则用户确认旧草案后，任务会被重新写回
 * 已删除的 {@code course_id}，违反 `PO-05`“删除课程必须解绑保留任务”的约束。
 * 草案清理注册在事务提交后执行，避免课程删除回滚时误删仍然有效的草案。
 */
@Component
public class CourseDeletingListener {

    private final DdlTaskMapper ddlTaskMapper;
    private final DdlDraftStore draftStore;

    public CourseDeletingListener(DdlTaskMapper ddlTaskMapper, DdlDraftStore draftStore) {
        this.ddlTaskMapper = ddlTaskMapper;
        this.draftStore = draftStore;
    }

    @EventListener
    public void onCourseDeleting(CourseDeletingEvent event) {
        List<Long> taskIds = ddlTaskMapper.selectTaskIdsByCourse(
                event.userId(), event.courseId());
        ddlTaskMapper.unbindCourse(event.userId(), event.courseId());
        for (Long taskId : taskIds) {
            draftStore.deleteDraftAfterCommit(event.userId(), taskId);
        }
    }
}
