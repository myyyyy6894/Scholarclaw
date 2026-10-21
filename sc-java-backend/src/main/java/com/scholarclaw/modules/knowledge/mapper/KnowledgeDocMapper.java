package com.scholarclaw.modules.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.scholarclaw.modules.knowledge.entity.KnowledgeDocEntity;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * {@code t_knowledge_doc} 的唯一 Mapper（{@code 03} §6.0：本表唯一写入模块 = `knowledge`）。
 *
 * <p>统计查询只按 {@code user_id + course_id} 过滤，绝不跨租户聚合。
 */
@Mapper
public interface KnowledgeDocMapper extends BaseMapper<KnowledgeDocEntity> {

    @Select("""
            <script>
            SELECT course_id,
                   SUM(CASE WHEN status = 'READY' THEN 1 ELSE 0 END) AS ready_count,
                   SUM(CASE WHEN status IN ('UPLOADED', 'PARSING') THEN 1 ELSE 0 END) AS parsing_count,
                   SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) AS failed_count,
                   COUNT(*) AS total_count
              FROM t_knowledge_doc
             WHERE user_id = #{userId}
               AND status NOT IN ('DELETING', 'DELETED')
               AND course_id IN
               <foreach collection="courseIds" item="courseId" open="(" separator="," close=")">
                 #{courseId}
               </foreach>
             GROUP BY course_id
            </script>
            """)
    List<CourseDocStatsRow> selectCourseStats(@Param("userId") Long userId,
                                              @Param("courseIds") Collection<Long> courseIds);
}
