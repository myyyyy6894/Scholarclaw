package com.scholarclaw.modules.knowledge.controller;

import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.common.response.Result;
import com.scholarclaw.common.security.SecurityContextUtils;
import com.scholarclaw.modules.knowledge.dto.DeleteDocVO;
import com.scholarclaw.modules.knowledge.dto.KnowledgeDocVO;
import com.scholarclaw.modules.knowledge.entity.KnowledgeDocEntity;
import com.scholarclaw.modules.knowledge.service.KnowledgeDocService;
import com.scholarclaw.modules.knowledge.service.KnowledgeIngestService;
import com.scholarclaw.modules.knowledge.service.KnowledgeVectorCleanupService;
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
 * `SC_MANUAL_INGEST_ENABLED=false` 时仍返回 `501 FEATURE_NOT_AVAILABLE`，用于关闭生产端；
 * 消费者（TASK-502）未接入前文档会停留在 `PARSING`，UI 入口仍保持隐藏。
 *
 * <p>`API-DOC-005` 只做第一阶段 `DELETING`；物理文件与向量清理由 TASK-901/902 的 GC 完成。
 */
@RestController
@RequestMapping("/api/v1/docs")
public class KnowledgeDocActionController {

    private final KnowledgeDocService docService;
    private final KnowledgeIngestService ingestService;
    private final KnowledgeVectorCleanupService vectorCleanupService;
    private final boolean manualIngestEnabled;

    public KnowledgeDocActionController(
            KnowledgeDocService docService,
            KnowledgeIngestService ingestService,
            KnowledgeVectorCleanupService vectorCleanupService,
            @Value("${sc.knowledge.manual-ingest-enabled:true}") boolean manualIngestEnabled) {
        this.docService = docService;
        this.ingestService = ingestService;
        this.vectorCleanupService = vectorCleanupService;
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
        vectorCleanupService.cleanupDocument(userId, deleted.getCourseId(), parsed, null);
        return Result.ok("已标记为 DELETING，物理文件与向量等待 TASK-901/902 GC 清理",
                new DeleteDocVO(parsed, deleted.getStatus()));
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
