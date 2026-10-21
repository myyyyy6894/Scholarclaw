package com.scholarclaw.modules.course.event;

/**
 * 课程删除事件（{@code 04} `API-COURSE-004`）。
 *
 * <p>由 `course` 模块在第一阶段软删事务内发布；`knowledge`、`ddl`、`agent` 模块分别监听并
 * 处理文档状态、DDL 任务解绑与课程会话清理。事件只携带身份与课程 ID，不携带任何凭据。
 */
public record CourseDeletingEvent(Long userId, Long courseId) {
}
