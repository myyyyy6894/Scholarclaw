package com.scholarclaw.modules.knowledge.controller;

import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.common.response.Result;
import com.scholarclaw.common.security.SecurityContextUtils;
import com.scholarclaw.modules.knowledge.dto.KnowledgeDocVO;
import com.scholarclaw.modules.knowledge.service.KnowledgeDocService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 课件文档查询接口（{@code 04} §4.4.1 `API-DOC-001`）。
 *
 * <p>上传/重试/重建/删除的 Web 接口分别由 TASK-403/501 落地；本任务先提供查询与状态机底座。
 */
@RestController
@RequestMapping("/api/v1/courses")
public class KnowledgeDocController {

    private final KnowledgeDocService knowledgeDocService;

    public KnowledgeDocController(KnowledgeDocService knowledgeDocService) {
        this.knowledgeDocService = knowledgeDocService;
    }

    @GetMapping("/{courseId}/docs")
    public Result<List<KnowledgeDocVO>> list(@PathVariable("courseId") String courseId,
                                             @RequestParam(value = "status", required = false) String status) {
        Long parsed = parseCourseId(courseId);
        return Result.ok(knowledgeDocService.listDocs(
                SecurityContextUtils.requireCurrentUserId(), parsed, status));
    }

    private static Long parseCourseId(String raw) {
        try {
            long value = Long.parseLong(raw);
            if (value <= 0) {
                throw new NumberFormatException("non-positive");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "course_id 必须为正的十进制字符串。");
        }
    }
}
