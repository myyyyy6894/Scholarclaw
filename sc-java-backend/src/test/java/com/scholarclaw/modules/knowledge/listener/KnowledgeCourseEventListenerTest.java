package com.scholarclaw.modules.knowledge.listener;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.scholarclaw.common.event.CourseDeletingEvent;
import com.scholarclaw.common.event.CourseEmbeddingChangedEvent;
import com.scholarclaw.modules.knowledge.entity.KnowledgeDocEntity;
import com.scholarclaw.modules.knowledge.entity.KnowledgeParentChunkEntity;
import com.scholarclaw.modules.knowledge.mapper.KnowledgeDocMapper;
import com.scholarclaw.modules.knowledge.mapper.KnowledgeParentChunkMapper;
import com.scholarclaw.support.MybatisTableInfoTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TASK-402：课程事件驱动文档状态与父块清理。 */
class KnowledgeCourseEventListenerTest {

    @BeforeAll
    static void initTableInfo() {
        MybatisTableInfoTestSupport.init(
                KnowledgeDocEntity.class, KnowledgeParentChunkEntity.class);
    }

    @Test
    @DisplayName("课程切换 Embedding：文档转 PARSING 并清空旧父块")
    void embeddingChangeMovesDocsToParsing() {
        KnowledgeDocMapper docMapper = mock(KnowledgeDocMapper.class);
        KnowledgeParentChunkMapper parentMapper = mock(KnowledgeParentChunkMapper.class);

        new CourseEmbeddingChangedListener(docMapper, parentMapper)
                .onCourseEmbeddingChanged(new CourseEmbeddingChangedEvent(
                        10001L, 2001L, 1, 2, "OPENAI_COMPATIBLE", "text-embedding-3-small", 1536));

        verify(docMapper).update(any(), any());
        verify(parentMapper).deleteByCourse(10001L, 2001L);
    }

    @Test
    @DisplayName("课程删除：文档转 DELETING 并清空该课程父块")
    void courseDeletingMovesDocsToDeleting() {
        KnowledgeDocMapper docMapper = mock(KnowledgeDocMapper.class);
        KnowledgeParentChunkMapper parentMapper = mock(KnowledgeParentChunkMapper.class);

        new CourseDeletingKnowledgeListener(docMapper, parentMapper)
                .onCourseDeleting(new CourseDeletingEvent(10001L, 2001L));

        verify(docMapper).update(any(), any());
        verify(parentMapper).deleteByCourse(10001L, 2001L);
    }
}
