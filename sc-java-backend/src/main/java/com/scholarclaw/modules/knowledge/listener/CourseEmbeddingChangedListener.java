package com.scholarclaw.modules.knowledge.listener;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.scholarclaw.common.enums.DocumentStatus;
import com.scholarclaw.modules.course.event.CourseEmbeddingChangedEvent;
import com.scholarclaw.modules.knowledge.entity.KnowledgeDocEntity;
import com.scholarclaw.modules.knowledge.mapper.KnowledgeDocMapper;
import com.scholarclaw.modules.knowledge.mapper.KnowledgeParentChunkMapper;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 课程切换 Embedding 后的文档状态切换（{@code 04} `API-COURSE-003` 第一阶段）。
 *
 * <p>只做 MySQL 状态与父块清理：所有活动文档置 `PARSING`、`parse_version + 1`、
 * 绑定新课程索引版本，并清空旧父块。`REINDEX` 消息的生产属 TASK-501；在此之前的阶段
 * 文档会停留在 `PARSING`，不会被误当作可检索文档。
 */
@Component
public class CourseEmbeddingChangedListener {

    private final KnowledgeDocMapper docMapper;
    private final KnowledgeParentChunkMapper parentChunkMapper;

    public CourseEmbeddingChangedListener(KnowledgeDocMapper docMapper,
                                          KnowledgeParentChunkMapper parentChunkMapper) {
        this.docMapper = docMapper;
        this.parentChunkMapper = parentChunkMapper;
    }

    @EventListener
    @Transactional(rollbackFor = Exception.class)
    public void onCourseEmbeddingChanged(CourseEmbeddingChangedEvent event) {
        docMapper.update(null, Wrappers.<KnowledgeDocEntity>lambdaUpdate()
                .eq(KnowledgeDocEntity::getUserId, event.userId())
                .eq(KnowledgeDocEntity::getCourseId, event.courseId())
                .notIn(KnowledgeDocEntity::getStatus,
                        DocumentStatus.DELETING.name(), DocumentStatus.DELETED.name())
                .set(KnowledgeDocEntity::getStatus, DocumentStatus.PARSING.name())
                .set(KnowledgeDocEntity::getCourseIndexVersion, event.currentIndexVersion())
                .set(KnowledgeDocEntity::getRetryCount, 0)
                .set(KnowledgeDocEntity::getErrorCode, null)
                .set(KnowledgeDocEntity::getErrorMsg, null)
                .set(KnowledgeDocEntity::getUpdatedAt,
                        com.scholarclaw.common.config.ScTimeZone.now().toLocalDateTime())
                .setSql("parse_version = parse_version + 1"));
        parentChunkMapper.deleteByCourse(event.userId(), event.courseId());
    }
}
