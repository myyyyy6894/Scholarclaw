package com.scholarclaw.modules.ddl.listener;

import com.scholarclaw.modules.course.event.CourseDeletingEvent;
import com.scholarclaw.modules.ddl.mapper.DdlTaskMapper;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 课程删除时解绑 DDL 任务（{@code 04} `API-COURSE-004`、`PO-05`）。
 *
 * <p>监听 `course` 模块在第一阶段软删事务内发布的 {@link CourseDeletingEvent}，在**同一数据库事务**内
 * 执行 {@code course_id = NULL}。`ddl` 是 {@code t_ddl_task} 的唯一写入者，因此不在
 * `course` 模块直接改表（Strict DAG + Single-Writer）。
 */
@Component
public class CourseDeletingListener {

    private final DdlTaskMapper ddlTaskMapper;

    public CourseDeletingListener(DdlTaskMapper ddlTaskMapper) {
        this.ddlTaskMapper = ddlTaskMapper;
    }

    @EventListener
    public void onCourseDeleting(CourseDeletingEvent event) {
        ddlTaskMapper.unbindCourse(event.userId(), event.courseId());
    }
}
