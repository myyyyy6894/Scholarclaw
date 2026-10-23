package com.scholarclaw.modules.knowledge.controller;

import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.common.response.Result;
import com.scholarclaw.modules.knowledge.dto.IngestResultRequest;
import com.scholarclaw.modules.knowledge.dto.IngestResultVO;
import com.scholarclaw.modules.knowledge.dto.ParentChunkBatchRequest;
import com.scholarclaw.modules.knowledge.dto.ParentChunkBatchVO;
import com.scholarclaw.modules.knowledge.dto.ParseContextVO;
import com.scholarclaw.modules.knowledge.service.KnowledgeDocService;
import com.scholarclaw.modules.knowledge.service.KnowledgeIngestResultService;
import com.scholarclaw.modules.knowledge.service.KnowledgeParseContextService;
import com.scholarclaw.modules.knowledge.service.ParentChunkService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Python → Java 内部解析接口（{@code 04} §4.9.5 / §4.9.6 / §4.9.7，TASK-503/602）。
 *
 * <p>`/internal/**` 由 `InternalTokenAuthFilter` 以常量时间令牌校验保护；
 * 本控制器再强制合法的十进制 `X-User-Id`，且必须与请求体 `user_id` 一致（回调）。
 */
@RestController
@RequestMapping("/internal/api/v1")
public class InternalRagCallbackController {

    private static final String USER_ID_HEADER = "X-User-Id";

    private final KnowledgeParseContextService parseContextService;
    private final KnowledgeIngestResultService ingestResultService;
    private final KnowledgeDocService docService;
    private final ParentChunkService parentChunkService;

    public InternalRagCallbackController(KnowledgeParseContextService parseContextService,
                                         KnowledgeIngestResultService ingestResultService,
                                         KnowledgeDocService docService,
                                         ParentChunkService parentChunkService) {
        this.parseContextService = parseContextService;
        this.ingestResultService = ingestResultService;
        this.docService = docService;
        this.parentChunkService = parentChunkService;
    }

    @GetMapping("/docs/{docId}/parse-context")
    public Result<ParseContextVO> parseContext(
            @PathVariable("docId") String docId,
            @RequestParam("parse_version") int parseVersion,
            @RequestParam("course_index_version") int courseIndexVersion,
            HttpServletRequest request) {
        Long userId = requireUserId(request);
        ParseContextVO context = parseContextService.resolve(
                userId, parseLong(docId, "doc_id"), parseVersion, courseIndexVersion);
        return Result.ok("Parse context verified", context);
    }

    @PostMapping("/rag/ingest-result")
    public Result<IngestResultVO> ingestResult(@Valid @RequestBody IngestResultRequest body,
                                               HttpServletRequest request) {
        Long userId = requireUserId(request);
        IngestResultVO result = ingestResultService.apply(
                userId, parseLong(body.docId(), "doc_id"), body);
        return Result.ok("Ingest result processed", result);
    }

    /**
     * `API-INT-JAVA-002`：批量权威回查父块全文（TASK-602）。
     *
     * <p>候选来自 ChromaDB 检索，但状态、归属、解析版本与课程索引版本一律以 MySQL 为准。
     */
    @PostMapping("/rag/parent-chunks/batch-get")
    public Result<ParentChunkBatchVO> batchGetParentChunks(
            @Valid @RequestBody ParentChunkBatchRequest body,
            HttpServletRequest request) {
        Long userId = requireUserId(request);
        ParentChunkBatchVO result = parentChunkService.batchGet(userId, body);
        return Result.ok("Parent chunks verified", result);
    }

    /**
     * 消费者内部重试计数（TASK-506 复核修复）：Python 在退避重试前上报一次，
     * 使 `retry_count` 真实反映重试次数，供巡检/排障使用。
     */
    @PostMapping("/docs/{docId}/retry-attempt")
    public Result<Boolean> recordRetryAttempt(
            @PathVariable("docId") String docId,
            @RequestParam("parse_version") int parseVersion,
            HttpServletRequest request) {
        Long userId = requireUserId(request);
        if (parseVersion < 1) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "parse_version 必须 >= 1。");
        }
        boolean recorded = docService.recordRetryAttempt(userId,
                parseLong(docId, "doc_id"), parseVersion);
        return Result.ok("Retry attempt recorded", recorded);
    }

    private static Long requireUserId(HttpServletRequest request) {
        String raw = request.getHeader(USER_ID_HEADER);
        if (raw == null || !raw.matches("\\d{1,19}")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "内部请求必须携带合法的 X-User-Id。");
        }
        Long userId = Long.valueOf(raw);
        // 内部令牌已由过滤器校验；这里把真实用户绑定到 MDC，保证 [user_id=...] 日志可检索
        com.scholarclaw.common.filter.TraceContext.bindUserId(userId);
        return userId;
    }

    private static Long parseLong(String raw, String field) {
        try {
            long value = Long.parseLong(raw);
            if (value <= 0) {
                throw new NumberFormatException("non-positive");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    field + " 必须为正的十进制字符串。");
        }
    }
}
