package com.scholarclaw.modules.knowledge.controller;

import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.common.response.Result;
import com.scholarclaw.modules.knowledge.dto.IngestResultRequest;
import com.scholarclaw.modules.knowledge.dto.IngestResultVO;
import com.scholarclaw.modules.knowledge.dto.ParseContextVO;
import com.scholarclaw.modules.knowledge.service.KnowledgeIngestResultService;
import com.scholarclaw.modules.knowledge.service.KnowledgeParseContextService;
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
 * Python → Java 内部解析接口（{@code 04} §4.9.5 / §4.9.7，TASK-503）。
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

    public InternalRagCallbackController(KnowledgeParseContextService parseContextService,
                                         KnowledgeIngestResultService ingestResultService) {
        this.parseContextService = parseContextService;
        this.ingestResultService = ingestResultService;
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

    private static Long requireUserId(HttpServletRequest request) {
        String raw = request.getHeader(USER_ID_HEADER);
        if (raw == null || !raw.matches("\\d{1,19}")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "内部请求必须携带合法的 X-User-Id。");
        }
        return Long.valueOf(raw);
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
