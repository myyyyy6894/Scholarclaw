package com.scholarclaw.modules.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.scholarclaw.modules.knowledge.entity.KnowledgeDocEntity;
import java.util.List;
import java.util.Map;
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
                   SUM(CASE WHEN status = 'READY'
                             AND (
                               <foreach collection="courseIndexVersions" index="courseId"
                                        item="indexVersion" separator=" OR ">
                                 (course_id = #{courseId} AND course_index_version = #{indexVersion})
                               </foreach>
                             )
                        THEN 1 ELSE 0 END) AS ready_count,
                   SUM(CASE WHEN status IN ('UPLOADED', 'PARSING') THEN 1 ELSE 0 END) AS parsing_count,
                   SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) AS failed_count,
                   COUNT(*) AS total_count
              FROM t_knowledge_doc
             WHERE user_id = #{userId}
               AND status NOT IN ('DELETING', 'DELETED')
               AND course_id IN
               <foreach collection="courseIndexVersions" index="courseId"
                        item="indexVersion" open="(" separator="," close=")">
                 #{courseId}
               </foreach>
             GROUP BY course_id
            </script>
            """)
    List<CourseDocStatsRow> selectCourseStats(
            @Param("userId") Long userId,
            @Param("courseIndexVersions") Map<Long, Integer> courseIndexVersions);

    /**
     * 当前读（不加缓存/快照）：用于乐观锁失败后判断文档是否已被并发版本接管。
     *
     * <p>MySQL 默认 REPEATABLE-READ 下普通 SELECT 会继续读事务开始时的快照，
     * 只有锁定读才能看到并发已提交的新版本。
     */
    @Select("""
            SELECT * FROM t_knowledge_doc
             WHERE doc_id = #{docId} AND user_id = #{userId}
             FOR UPDATE
            """)
    KnowledgeDocEntity selectByIdForUpdate(@Param("docId") Long docId,
                                           @Param("userId") Long userId);
}
