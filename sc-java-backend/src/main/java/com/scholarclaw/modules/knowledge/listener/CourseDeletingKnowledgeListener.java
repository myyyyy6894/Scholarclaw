package com.scholarclaw.modules.knowledge.listener;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.scholarclaw.common.config.ScTimeZone;
import com.scholarclaw.common.enums.DocumentStatus;
import com.scholarclaw.common.event.CourseDeletingEvent;
import com.scholarclaw.modules.knowledge.entity.KnowledgeDocEntity;
import com.scholarclaw.modules.knowledge.mapper.KnowledgeDocMapper;
import com.scholarclaw.modules.knowledge.mapper.KnowledgeParentChunkMapper;
import com.scholarclaw.modules.knowledge.service.KnowledgeDocService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 课程删除时的文档第一阶段隔离（{@code 04} `API-COURSE-004`）。
 *
 * <p>与 `course` 软删在同一本地事务内：活动文档置 `DELETING`、`parse_version + 1`
 * （拦截在途回调），并立即清空该课程父块。物理文件与向量清理由 TASK-901/902 的 GC 完成。
 */
@Component
public class CourseDeletingKnowledgeListener {

    private final KnowledgeDocMapper docMapper;
    private final KnowledgeParentChunkMapper parentChunkMapper;

    public CourseDeletingKnowledgeListener(KnowledgeDocMapper docMapper,
                                           KnowledgeParentChunkMapper parentChunkMapper) {
        this.docMapper = docMapper;
        this.parentChunkMapper = parentChunkMapper;
    }

    @EventListener
    @Transactional(rollbackFor = Exception.class)
    public void onCourseDeleting(CourseDeletingEvent event) {
        docMapper.update(null, Wrappers.<KnowledgeDocEntity>lambdaUpdate()
                .eq(KnowledgeDocEntity::getUserId, event.userId())
                .eq(KnowledgeDocEntity::getCourseId, event.courseId())
                .notIn(KnowledgeDocEntity::getStatus,
                        DocumentStatus.DELETING.name(), DocumentStatus.DELETED.name())
                .set(KnowledgeDocEntity::getStatus, DocumentStatus.DELETING.name())
                .set(KnowledgeDocEntity::getUpdatedAt, ScTimeZone.now().toLocalDateTime())
                .setSql("parse_version = parse_version + 1, "
                        + KnowledgeDocService.CLEAR_INDEX_SNAPSHOT_SQL));
        parentChunkMapper.deleteByCourse(event.userId(), event.courseId());
    }
}
