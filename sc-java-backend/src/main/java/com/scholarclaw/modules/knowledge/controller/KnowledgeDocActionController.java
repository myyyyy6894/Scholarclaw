package com.scholarclaw.modules.knowledge.controller;

import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.common.response.Result;
import com.scholarclaw.common.security.SecurityContextUtils;
import com.scholarclaw.modules.knowledge.dto.DeleteDocVO;
import com.scholarclaw.modules.knowledge.dto.KnowledgeDocVO;
import com.scholarclaw.modules.knowledge.entity.KnowledgeDocEntity;
import com.scholarclaw.modules.knowledge.service.KnowledgeDocService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 单个课件文档的状态操作（{@code 04} §4.4.3~4.4.5 `API-DOC-003/004/005`）。
 *
 * <p><b>P4 门禁：</b>`API-DOC-003/004` 依赖 TASK-501 的 `RETRY_FAILED`/`REINDEX` MQ 生产者。
 * 在生产者落地前，接口默认返回 `501 FEATURE_NOT_AVAILABLE`，绝不把文档改成没有消费者的
 * `PARSING`。`KnowledgeDocService.retryFailed/reindexReady` 保留为 P5 的领域服务基础。
 *
 * <p>`API-DOC-005` 只做第一阶段 `DELETING`；物理文件与向量清理由 TASK-901/902 的 GC 完成。
 */
@RestController
@RequestMapping("/api/v1/docs")
public class KnowledgeDocActionController {

    private final KnowledgeDocService docService;
    private final boolean manualIngestEnabled;

    public KnowledgeDocActionController(
            KnowledgeDocService docService,
            @Value("${sc.knowledge.manual-ingest-enabled:false}") boolean manualIngestEnabled) {
        this.docService = docService;
        this.manualIngestEnabled = manualIngestEnabled;
    }

    @PostMapping("/{docId}/retry")
    public Result<KnowledgeDocVO> retry(@PathVariable("docId") String docId) {
        Long parsed = parseDocId(docId);
        requireManualIngestAvailable();
        KnowledgeDocEntity updated = docService.retryFailed(
                SecurityContextUtils.requireCurrentUserId(), parsed);
        return Result.ok("课件已重新进入解析队列", docService.toVO(updated));
    }

    @PostMapping("/{docId}/reindex")
    public Result<KnowledgeDocVO> reindex(@PathVariable("docId") String docId) {
        Long parsed = parseDocId(docId);
        requireManualIngestAvailable();
        KnowledgeDocEntity updated = docService.reindexReady(
                SecurityContextUtils.requireCurrentUserId(), parsed);
        return Result.ok("课件已重新进入向量重建队列", docService.toVO(updated));
    }

    @DeleteMapping("/{docId}")
    public Result<DeleteDocVO> delete(@PathVariable("docId") String docId) {
        Long parsed = parseDocId(docId);
        KnowledgeDocEntity deleted = docService.softDelete(
                SecurityContextUtils.requireCurrentUserId(), parsed);
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
