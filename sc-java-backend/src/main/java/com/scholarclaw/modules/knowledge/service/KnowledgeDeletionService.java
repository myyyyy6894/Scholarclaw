package com.scholarclaw.modules.knowledge.service;

import com.scholarclaw.modules.knowledge.entity.KnowledgeDocEntity;
import com.scholarclaw.modules.knowledge.mapper.KnowledgeParentChunkMapper;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 文档/课程/账号删除的第二阶段资源清理（{@code 06} TASK-901）。
 *
 * <p>数据库状态在第一阶段（同一业务事务）完成冻结；本服务负责在事务提交后按
 * “ChromaDB 向量 → 共享卷文件 → MySQL 终态/物理删除”的顺序清理，且**任何外部资源失败都不得
 * 被当作成功**：单文档/课程保留 `DELETING` 文档行，账号保留 `DEACTIVATING` 用户行，
 * 由 TASK-902 的 GC 继续重试。向量失败另由 `KnowledgeVectorCleanupService` 写入补偿队列。
 */
@Service
public class KnowledgeDeletionService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeDeletionService.class);

    private final KnowledgeVectorCleanupService vectorCleanupService;
    private final StorageService storageService;
    private final KnowledgeDocService docService;
    private final KnowledgeParentChunkMapper parentChunkMapper;

    public KnowledgeDeletionService(KnowledgeVectorCleanupService vectorCleanupService,
                                    StorageService storageService,
                                    KnowledgeDocService docService,
                                    KnowledgeParentChunkMapper parentChunkMapper) {
        this.vectorCleanupService = vectorCleanupService;
        this.storageService = storageService;
        this.docService = docService;
        this.parentChunkMapper = parentChunkMapper;
    }

    /**
     * 单文档删除：向量 → 文件 → `DELETED`。
     *
     * <p>文件删除失败时保留 `DELETING`；向量失败由补偿队列兜底，不阻断终态，
     * 因为文档行保留且 `docId` 仍在补偿消息里。
     *
     * @return true 表示文件已删除且文档已置 `DELETED`
     */
    public boolean finalizeDocumentDeletion(Long userId, KnowledgeDocEntity doc,
                                            Integer staleParseVersion) {
        boolean vectorsOk = vectorCleanupService.cleanupDocument(userId, doc.getCourseId(),
                doc.getDocId(), staleParseVersion);
        boolean fileOk = storageService.deleteRawByRelativePath(doc.getStorageRelPath());
        if (!fileOk) {
            log.warn("单文档物理文件清理未完成，保留 DELETING docId={}", doc.getDocId());
            return false;
        }
        if (!vectorsOk) {
            log.warn("单文档向量清理已进入补偿队列 docId={}", doc.getDocId());
        }
        return docService.markDeleted(userId, doc.getDocId());
    }

    /**
     * 课程删除：整课 Collection → 课程 raw 目录 → 文档 `DELETED`。
     *
     * <p>课程行可能已由 `course` 模块物理删除；这里的清理只依赖课程 ID 与文档行。
     *
     * @return true 表示目录已删除且所有活动文档已置 `DELETED`
     */
    public boolean finalizeCourseDeletion(Long userId, Long courseId, Integer courseIndexVersion) {
        boolean vectorsOk = vectorCleanupService.cleanupCourse(userId, courseId,
                courseIndexVersion);
        boolean dirOk = storageService.deleteRawDirectoryForCourse(userId, courseId);
        if (!dirOk) {
            log.warn("课程物理目录清理未完成，保留 DELETING courseId={}", courseId);
            return false;
        }
        if (!vectorsOk) {
            log.warn("课程向量清理已进入补偿队列 courseId={}", courseId);
        }
        List<KnowledgeDocEntity> docs = docService.listByCourse(userId, courseId);
        boolean allMarked = true;
        for (KnowledgeDocEntity doc : docs) {
            if ("DELETED".equals(doc.getStatus())) {
                continue;
            }
            if (!docService.markDeleted(userId, doc.getDocId())) {
                allMarked = false;
            }
        }
        return allMarked;
    }

    /**
     * 账号注销：用户全部向量 → `/raw/{uid}` 目录 → 父块 → 文档行物理删除。
     *
     * <p>只有向量与文件都成功时才删除 MySQL 行；否则保留行与 `DEACTIVATING` 用户标记，
     * 交给 GC 重试，避免残留数据失去查询入口。
     */
    public boolean finalizeUserDeletion(Long userId) {
        boolean vectorsOk = vectorCleanupService.cleanupUser(userId);
        boolean dirOk = storageService.deleteRawDirectoryForUser(userId);
        if (!vectorsOk || !dirOk) {
            log.warn("账号资源清理未完成，保留 DEACTIVATING userId={} vectors={} files={}",
                    userId, vectorsOk, dirOk);
            return false;
        }
        parentChunkMapper.deleteByUser(userId);
        docService.deleteAllByUser(userId);
        return true;
    }
}
