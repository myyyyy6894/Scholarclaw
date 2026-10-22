package com.scholarclaw.modules.knowledge.controller;

import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.common.response.Result;
import com.scholarclaw.common.security.SecurityContextUtils;
import com.scholarclaw.modules.knowledge.dto.KnowledgeDocVO;
import com.scholarclaw.modules.knowledge.service.KnowledgeDocService;
import com.scholarclaw.modules.knowledge.service.KnowledgeUploadService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 课件文档查询接口（{@code 04} §4.4.1 `API-DOC-001`）。
 *
 * <p>本控制器提供查询（TASK-402）与上传（TASK-403）接口；重试/重建/删除由
 * {@link KnowledgeDocActionController} 提供，其中重试/重建在 TASK-501/505 生产者接入前
 * 返回 `501 FEATURE_NOT_AVAILABLE`。
 */
@RestController
@RequestMapping("/api/v1/courses")
public class KnowledgeDocController {

    private final KnowledgeDocService knowledgeDocService;
    private final KnowledgeUploadService knowledgeUploadService;

    public KnowledgeDocController(KnowledgeDocService knowledgeDocService,
                                  KnowledgeUploadService knowledgeUploadService) {
        this.knowledgeDocService = knowledgeDocService;
        this.knowledgeUploadService = knowledgeUploadService;
    }

    @GetMapping("/{courseId}/docs")
    public Result<List<KnowledgeDocVO>> list(@PathVariable("courseId") String courseId,
                                             @RequestParam(value = "status", required = false) String status) {
        Long parsed = parseCourseId(courseId);
        return Result.ok(knowledgeDocService.listDocs(
                SecurityContextUtils.requireCurrentUserId(), parsed, status));
    }

    /**
     * `API-DOC-002`：上传课件并创建 `UPLOADED` 元数据。
     *
     * <p>返回 `202 Accepted`：文件已安全落盘，但异步解析（TASK-501）尚未开始。
     */
    @PostMapping(value = "/{courseId}/docs/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Result<KnowledgeDocVO> upload(@PathVariable("courseId") String courseId,
                                         @RequestPart("file") MultipartFile file) {
        Long parsed = parseCourseId(courseId);
        return Result.ok("课件已上传，等待异步解析", knowledgeUploadService.upload(
                SecurityContextUtils.requireCurrentUserId(), parsed, file));
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
