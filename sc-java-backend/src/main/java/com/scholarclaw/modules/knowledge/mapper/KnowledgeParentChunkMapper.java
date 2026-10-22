package com.scholarclaw.modules.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.scholarclaw.modules.knowledge.entity.KnowledgeParentChunkEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** {@code t_knowledge_parent_chunk} 的清理入口（Single-Writer = `knowledge`）。 */
@Mapper
public interface KnowledgeParentChunkMapper extends BaseMapper<KnowledgeParentChunkEntity> {

    @Delete("DELETE FROM t_knowledge_parent_chunk WHERE user_id = #{userId} AND doc_id = #{docId}")
    int deleteByDoc(@Param("userId") Long userId, @Param("docId") Long docId);

    @Delete("DELETE FROM t_knowledge_parent_chunk WHERE user_id = #{userId} AND course_id = #{courseId}")
    int deleteByCourse(@Param("userId") Long userId, @Param("courseId") Long courseId);
}
