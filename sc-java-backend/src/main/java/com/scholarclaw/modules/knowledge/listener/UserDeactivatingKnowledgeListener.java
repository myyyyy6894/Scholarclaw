package com.scholarclaw.modules.knowledge.listener;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.scholarclaw.common.config.ScTimeZone;
import com.scholarclaw.common.enums.DocumentStatus;
import com.scholarclaw.common.event.UserCleanupCompletedEvent;
import com.scholarclaw.common.event.UserDeactivatingEvent;
import com.scholarclaw.modules.knowledge.entity.KnowledgeDocEntity;
import com.scholarclaw.modules.knowledge.mapper.KnowledgeDocMapper;
import com.scholarclaw.modules.knowledge.mapper.KnowledgeParentChunkMapper;
import com.scholarclaw.modules.knowledge.service.KnowledgeDeletionService;
import com.scholarclaw.modules.knowledge.service.KnowledgeDocService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 账号注销时知识库数据的冻结与清理（{@code 04} `API-USER-006`、{@code 06} TASK-901）。
 *
 * <p>第一阶段与账号状态更新同事务：全部文档置 `DELETING`、`parse_version + 1` 拦截在途回调、
 * 物理清空父块。第二阶段在事务提交后清理用户全部向量与 `/raw/{uid}` 目录，成功后才物理删除
 * 文档行并上报完成；任一步失败都保留 `DEACTIVATING` 账号标记，交给 TASK-902 的 GC 重试。
 */
@Component
public class UserDeactivatingKnowledgeListener {

    private static final Logger log = LoggerFactory.getLogger(
            UserDeactivatingKnowledgeListener.class);

    private final KnowledgeDocMapper docMapper;
    private final KnowledgeParentChunkMapper parentChunkMapper;
    private final KnowledgeDeletionService deletionService;
    private final ApplicationEventPublisher eventPublisher;

    public UserDeactivatingKnowledgeListener(KnowledgeDocMapper docMapper,
                                             KnowledgeParentChunkMapper parentChunkMapper,
                                             KnowledgeDeletionService deletionService,
                                             ApplicationEventPublisher eventPublisher) {
        this.docMapper = docMapper;
        this.parentChunkMapper = parentChunkMapper;
        this.deletionService = deletionService;
        this.eventPublisher = eventPublisher;
    }

    @EventListener
    public void freezeDocuments(UserDeactivatingEvent event) {
        int updated = docMapper.update(null, Wrappers.<KnowledgeDocEntity>lambdaUpdate()
                .eq(KnowledgeDocEntity::getUserId, event.userId())
                .notIn(KnowledgeDocEntity::getStatus,
                        DocumentStatus.DELETING.name(), DocumentStatus.DELETED.name())
                .set(KnowledgeDocEntity::getStatus, DocumentStatus.DELETING.name())
                .set(KnowledgeDocEntity::getUpdatedAt, ScTimeZone.now().toLocalDateTime())
                .setSql("parse_version = parse_version + 1, "
                        + KnowledgeDocService.CLEAR_INDEX_SNAPSHOT_SQL));
        int chunks = parentChunkMapper.deleteByUser(event.userId());
        log.info("账号注销：知识库已冻结 userId={} docs={} parentChunks={}",
                event.userId(), updated, chunks);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void purgeKnowledge(UserDeactivatingEvent event) {
        if (!deletionService.finalizeUserDeletion(event.userId())) {
            log.warn("账号注销：知识库资源清理未完成 userId={}", event.userId());
            return;
        }
        eventPublisher.publishEvent(
                new UserCleanupCompletedEvent(event.userId(),
                        UserCleanupCompletedEvent.Module.KNOWLEDGE));
    }
}
