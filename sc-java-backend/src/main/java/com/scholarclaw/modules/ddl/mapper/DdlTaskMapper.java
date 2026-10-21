package com.scholarclaw.modules.ddl.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * {@code t_ddl_task} 的唯一 Mapper（{@code 03} §6.0：本表唯一写入模块 = `ddl`）。
 *
 * <p>当前只提供 TASK-401 删除课程所需的“解绑保留”更新；完整的 DDL 任务读写由
 * TASK-701 在此基础上扩展。
 */
@Mapper
public interface DdlTaskMapper {

    /** 删除课程时解绑关联任务：保留 {@code course_name} 文本，仅把 {@code course_id} 置空。 */
    @Update("""
            UPDATE t_ddl_task
               SET course_id = NULL, updated_at = NOW(3)
             WHERE course_id = #{courseId}
               AND user_id = #{userId}
            """)
    int unbindCourse(@Param("userId") Long userId, @Param("courseId") Long courseId);
}
