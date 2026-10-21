package com.scholarclaw.modules.userconfig.service;

/**
 * 课程数量只读端口（解决 Strict DAG：`userconfig` 不得依赖 `course`）。
 *
 * <p>`course` 模块实现本接口；`API-USER-003` 用它返回 `existing_course_count`，
 * 供前端判断是否需要提示用户切换已有课程的 Embedding 模型。
 */
public interface UserCourseCountProvider {

    int countActiveCourses(Long userId);
}
