package com.scholarclaw.modules.knowledge.service;

import com.scholarclaw.modules.knowledge.entity.KnowledgeDocEntity;
import com.scholarclaw.modules.knowledge.mapper.KnowledgeParentChunkMapper;
import com.scholarclaw.modules.knowledge.service.KnowledgeVectorCleanupService.VectorCleanupResult;
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
     * <p><b>终态门禁（P9 复核）：</b>只有向量已确认清理，或补偿已成功写入
     * {@code t_vector_cleanup_outbox} 时，才允许把文档置为 `DELETED`；否则文件可以删除，
     * 但文档必须保留 `DELETING`，由 GC 继续重试，绝不能“文件没了、向量还在、行已终态”。
     *
     * @return true 表示文件已删除、向量已有交代且文档已置 `DELETED`
     */
    public boolean finalizeDocumentDeletion(Long userId, KnowledgeDocEntity doc,
                                            Integer staleParseVersion) {
        VectorCleanupResult vectors = vectorCleanupService.cleanupDocument(userId,
                doc.getCourseId(), doc.getDocId(), staleParseVersion);
        boolean fileOk = storageService.deleteRawByRelativePath(doc.getStorageRelPath());
        if (!fileOk) {
            log.warn("单文档物理文件清理未完成，保留 DELETING docId={}", doc.getDocId());
            return false;
        }
        if (!vectors.handled()) {
            log.error("单文档向量未确认且补偿未落库，保留 DELETING docId={}", doc.getDocId());
            return false;
        }
        if (!vectors.confirmed()) {
            log.warn("单文档向量清理已进入持久化补偿 docId={}", doc.getDocId());
        }
        return docService.markDeleted(userId, doc.getDocId());
    }

    /**
     * 课程删除：整课 Collection → 课程 raw 目录 → 文档 `DELETED`。
     *
     * <p>课程行可能已由 `course` 模块物理删除；这里的清理只依赖课程 ID 与文档行。
     * 只有向量已确认或补偿已落库、目录已删除、全部文档已置 `DELETED` 时才返回 true，
     * 调用方（GC / 事件监听）据此决定是否物理删除课程行。
     *
     * @return true 表示目录已删除、向量已有交代且所有活动文档已置 `DELETED`
     */
    public boolean finalizeCourseDeletion(Long userId, Long courseId, Integer courseIndexVersion) {
        VectorCleanupResult vectors = vectorCleanupService.cleanupCourse(userId, courseId,
                courseIndexVersion);
        boolean dirOk = storageService.deleteRawDirectoryForCourse(userId, courseId);
        if (!dirOk) {
            log.warn("课程物理目录清理未完成，保留 DELETING courseId={}", courseId);
            return false;
        }
        if (!vectors.handled()) {
            log.error("课程向量未确认且补偿未落库，保留 DELETING courseId={}", courseId);
            return false;
        }
        if (!vectors.confirmed()) {
            log.warn("课程向量清理已进入持久化补偿 courseId={}", courseId);
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
     * <p>只有向量与文件都成功（或向量补偿已落库）时才删除 MySQL 行；否则保留行与
     * `DEACTIVATING` 用户标记，交给 GC 重试，避免残留数据失去查询入口。
     */
    public boolean finalizeUserDeletion(Long userId) {
        VectorCleanupResult vectors = vectorCleanupService.cleanupUser(userId);
        boolean dirOk = storageService.deleteRawDirectoryForUser(userId);
        if (!vectors.handled() || !dirOk) {
            log.warn("账号资源清理未完成，保留 DEACTIVATING userId={} vectors={} files={}",
                    userId, vectors.confirmed(), dirOk);
            return false;
        }
        parentChunkMapper.deleteByUser(userId);
        docService.deleteAllByUser(userId);
        return true;
    }
}
