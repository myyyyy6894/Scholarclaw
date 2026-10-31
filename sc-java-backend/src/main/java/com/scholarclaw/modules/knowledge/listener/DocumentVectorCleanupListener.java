package com.scholarclaw.modules.knowledge.listener;

import com.scholarclaw.common.event.DocumentVectorCleanupEvent;
import com.scholarclaw.modules.knowledge.service.KnowledgeVectorCleanupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 在业务事务提交后清理衍生向量（TASK-506 复核修复）。
 *
 * <p>ChromaDB 是派生索引：清理不能参与业务事务，否则网络超时会拖长/回滚业务状态。
 * 因此数据库侧只负责发布事件，真正的删除在 `AFTER_COMMIT` 执行。清理服务会先立即执行，
 * 瞬态失败重试一次，仍失败写入持久化补偿队列；TASK-901/902 的 GC 仅作最终兜底。
 */
@Component
public class DocumentVectorCleanupListener {

    private static final Logger log = LoggerFactory.getLogger(DocumentVectorCleanupListener.class);

    private final KnowledgeVectorCleanupService cleanupService;

    public DocumentVectorCleanupListener(KnowledgeVectorCleanupService cleanupService) {
        this.cleanupService = cleanupService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onDocumentVectorCleanup(DocumentVectorCleanupEvent event) {
        var result = cleanupService.cleanupDocument(event.userId(), event.courseId(),
                event.docId(), event.parseVersion());
        if (!result.handled()) {
            log.warn("文档向量补偿清理未完成且未落库 docId={} parseVersion={}",
                    event.docId(), event.parseVersion());
        }
    }
}
