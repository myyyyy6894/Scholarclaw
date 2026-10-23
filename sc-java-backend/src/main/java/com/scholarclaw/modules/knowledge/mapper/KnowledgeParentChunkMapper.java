package com.scholarclaw.modules.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.scholarclaw.modules.knowledge.entity.KnowledgeParentChunkEntity;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** {@code t_knowledge_parent_chunk} 的清理与权威回查入口（Single-Writer = `knowledge`）。 */
@Mapper
public interface KnowledgeParentChunkMapper extends BaseMapper<KnowledgeParentChunkEntity> {

    @Delete("DELETE FROM t_knowledge_parent_chunk WHERE user_id = #{userId} AND doc_id = #{docId}")
    int deleteByDoc(@Param("userId") Long userId, @Param("docId") Long docId);

    @Delete("DELETE FROM t_knowledge_parent_chunk WHERE user_id = #{userId} AND course_id = #{courseId}")
    int deleteByCourse(@Param("userId") Long userId, @Param("courseId") Long courseId);

    /**
     * `API-INT-JAVA-002` 的四重权威校验（TASK-602，`04` §4.9.6 / `02` §5.2.2）。
     *
     * <p>只有同时满足租户归属、文档 `READY`、解析版本一致、课程索引版本一致且课程未删除的
     * 父块才会返回；ChromaDB 元数据不参与任何判定。
     */
    @Select("""
            <script>
            SELECT p.parent_chunk_id, p.doc_id, d.doc_name,
                   p.page_start, p.page_end, p.section_title, p.content
              FROM t_knowledge_parent_chunk p
             INNER JOIN t_knowledge_doc d ON p.doc_id = d.doc_id
             INNER JOIN t_course_namespace c ON d.course_id = c.course_id
             WHERE p.parent_chunk_id IN
             <foreach collection="parentChunkIds" item="parentChunkId"
                      open="(" separator="," close=")">
               #{parentChunkId}
             </foreach>
               AND p.user_id = #{userId} AND p.course_id = #{courseId}
               AND d.user_id = #{userId} AND c.user_id = #{userId} AND c.is_deleting = 0
               AND d.status = 'READY'
               AND p.parse_version = d.parse_version
               AND d.course_index_version = #{expectedCourseIndexVersion}
               AND c.index_version = #{expectedCourseIndexVersion}
            </script>
            """)
    List<AuthoritativeParentChunkRow> selectAuthoritativeBatch(
            @Param("userId") Long userId,
            @Param("courseId") Long courseId,
            @Param("expectedCourseIndexVersion") int expectedCourseIndexVersion,
            @Param("parentChunkIds") List<String> parentChunkIds);
}
