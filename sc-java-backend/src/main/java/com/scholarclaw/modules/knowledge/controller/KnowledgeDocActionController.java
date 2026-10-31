package com.scholarclaw.modules.knowledge.controller;

import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.common.enums.DocumentStatus;
import com.scholarclaw.common.response.Result;
import com.scholarclaw.common.security.SecurityContextUtils;
import com.scholarclaw.modules.course.service.CourseService;
import com.scholarclaw.modules.knowledge.dto.DeleteDocVO;
import com.scholarclaw.modules.knowledge.dto.KnowledgeDocVO;
import com.scholarclaw.modules.knowledge.entity.KnowledgeDocEntity;
import com.scholarclaw.modules.knowledge.service.KnowledgeDeletionService;
import com.scholarclaw.modules.knowledge.service.KnowledgeDocService;
import com.scholarclaw.modules.knowledge.service.KnowledgeIngestService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 单个课件文档的状态操作（{@code 04} §4.4.3~4.4.5 `API-DOC-003/004/005`）。
 *
 * <p><b>TASK-501：</b>`API-DOC-003/004` 在数据库状态迁移提交后发布 `RETRY_FAILED`/`REINDEX`。
 * `SC_MANUAL_INGEST_ENABLED=false` 时仍返回 `501 FEATURE_NOT_AVAILABLE`，用于关闭生产端。
 * TASK-506 起消费者默认开启，Web 入口按文档状态展示重试/重建。
 *
 * <p>`API-DOC-005` 分两阶段：第一阶段 `DELETING` + 清空父块；提交后立即清理向量、物理文件并置
 * `DELETED`。外部资源失败时保留 `DELETING`，由 TASK-902 的 GC 继续重试（不作为成功返回）。
 */
@RestController
@RequestMapping("/api/v1/docs")
public class KnowledgeDocActionController {

    private final KnowledgeDocService docService;
    private final KnowledgeIngestService ingestService;
    private final KnowledgeDeletionService deletionService;
    private final CourseService courseService;
    private final boolean manualIngestEnabled;

    public KnowledgeDocActionController(
            KnowledgeDocService docService,
            KnowledgeIngestService ingestService,
            KnowledgeDeletionService deletionService,
            CourseService courseService,
            @Value("${sc.knowledge.manual-ingest-enabled:true}") boolean manualIngestEnabled) {
        this.docService = docService;
        this.ingestService = ingestService;
        this.deletionService = deletionService;
        this.courseService = courseService;
        this.manualIngestEnabled = manualIngestEnabled;
    }

    @PostMapping("/{docId}/retry")
    public Result<KnowledgeDocVO> retry(@PathVariable("docId") String docId) {
        Long parsed = parseDocId(docId);
        requireManualIngestAvailable();
        KnowledgeDocVO updated = ingestService.retry(
                SecurityContextUtils.requireCurrentUserId(), parsed);
        return Result.ok("课件已重新进入解析队列", updated);
    }

    @PostMapping("/{docId}/reindex")
    public Result<KnowledgeDocVO> reindex(@PathVariable("docId") String docId) {
        Long parsed = parseDocId(docId);
        requireManualIngestAvailable();
        KnowledgeDocVO updated = ingestService.reindex(
                SecurityContextUtils.requireCurrentUserId(), parsed);
        return Result.ok("课件已重新进入向量重建队列", updated);
    }

    @DeleteMapping("/{docId}")
    public Result<DeleteDocVO> delete(@PathVariable("docId") String docId) {
        Long parsed = parseDocId(docId);
        Long userId = SecurityContextUtils.requireCurrentUserId();
        KnowledgeDocEntity deleted = docService.softDelete(userId, parsed);
        boolean finalized;
        if (DocumentStatus.DELETED.name().equals(deleted.getStatus())) {
            // 墓碑幂等：同一用户重复删除已 DELETED 文档，返回 200 与原状态，不再重放清理。
            finalized = true;
        } else {
            finalized = deletionService.finalizeDocumentDeletion(userId, deleted, null);
            // 删除可能是 REBUILD_FAILED 课程里最后一份阻塞文档，提交后按真实统计收敛
            courseService.refreshIndexStatus(userId, deleted.getCourseId(),
                    deleted.getCourseIndexVersion() == null ? 1
                            : deleted.getCourseIndexVersion());
        }
        KnowledgeDocEntity current = docService.requireOwnedDoc(userId, parsed);
        String message = finalized
                ? "文档已删除：向量、物理文件与元数据清理完成"
                : "已标记为 DELETING；向量或物理文件清理未完成，将由后台 GC 重试";
        return Result.ok(message, new DeleteDocVO(parsed, current.getStatus()));
    }

    /**
     * 手动重试/重建的 P4 门禁。生产者在 TASK-501/505 接入后通过配置开启；
     * 关闭时不得产生任何状态变更。
     */
    private void requireManualIngestAvailable() {
        if (!manualIngestEnabled) {
            throw new BusinessException(ErrorCode.FEATURE_NOT_AVAILABLE,
                    "手动重试/重建尚未开放：待 TASK-501/505 接入异步解析与重建任务后可用。");
        }
    }

    private static Long parseDocId(String raw) {
        try {
            long value = Long.parseLong(raw);
            if (value <= 0) {
                throw new NumberFormatException("non-positive");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "doc_id 必须为正的十进制字符串。");
        }
    }
}
