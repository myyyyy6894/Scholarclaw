package com.scholarclaw.modules.course.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.scholarclaw.modules.course.entity.CourseNamespaceEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * {@code t_course_namespace} 的唯一 Mapper（{@code 03} §6.0：本表唯一写入模块 = `course`）。
 *
 * <p>默认课程查询命中生成列唯一索引 {@code uk_course_user_default}，用于并发懒加载失败后的
 * “赢家回查”；普通课程列表统一按 {@code user_id} 过滤，禁止跨用户读取。
 */
@Mapper
public interface CourseNamespaceMapper extends BaseMapper<CourseNamespaceEntity> {

    @Select("""
            SELECT * FROM t_course_namespace
            WHERE default_course_uk = #{userId}
              AND user_id = #{userId}
              AND is_default = 1
              AND is_deleting = 0
            LIMIT 1
            """)
    CourseNamespaceEntity selectActiveDefaultCourse(@Param("userId") Long userId);

    /**
     * 锁定当前用户的活动课程行（{@code SELECT ... FOR UPDATE}）。
     *
     * <p>上传、切换 Embedding、删除课程必须在同一事务内先锁该行，避免
     * “校验通过后课程被并发删除/重建”的窗口；跨用户查询不会命中。
     */
    @Select("""
            SELECT * FROM t_course_namespace
            WHERE course_id = #{courseId}
              AND user_id = #{userId}
              AND is_deleting = 0
            FOR UPDATE
            """)
    CourseNamespaceEntity selectActiveCourseForUpdate(@Param("userId") Long userId,
                                                      @Param("courseId") Long courseId);

    /**
     * 锁定当前用户的课程行（不过滤 `is_deleting`），用于删除幂等判定：
     * 并发删除时第二个请求会等待行锁，随后看到 `is_deleting=1` 并返回“已删除”。
     */
    @Select("""
            SELECT * FROM t_course_namespace
            WHERE course_id = #{courseId}
              AND user_id = #{userId}
            FOR UPDATE
            """)
    CourseNamespaceEntity selectCourseForUpdate(@Param("userId") Long userId,
                                                @Param("courseId") Long courseId);
}
