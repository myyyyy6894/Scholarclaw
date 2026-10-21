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
}
