package com.scholarclaw.modules.agent.controller;

import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.common.response.Result;
import com.scholarclaw.common.security.SecurityContextUtils;
import com.scholarclaw.modules.agent.dto.ChatMessagePageVO;
import com.scholarclaw.modules.agent.dto.ClearMessagesVO;
import com.scholarclaw.modules.agent.dto.RagQaRequest;
import com.scholarclaw.modules.agent.service.ChatMessageService;
import com.scholarclaw.modules.agent.service.ChatWindowService;
import com.scholarclaw.modules.agent.service.RagQaService;
import com.scholarclaw.modules.course.service.CourseService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** `agent` 模块 Web 接口：RAG SSE 问答与会话历史（`04` §4.5，TASK-604）。 */
@RestController
public class RagController {

    private static final int MAX_PAGE_LIMIT = 50;

    private final RagQaService ragQaService;
    private final ChatMessageService chatMessageService;
    private final ChatWindowService chatWindowService;
    private final CourseService courseService;

    public RagController(RagQaService ragQaService,
                         ChatMessageService chatMessageService,
                         ChatWindowService chatWindowService,
                         CourseService courseService) {
        this.ragQaService = ragQaService;
        this.chatMessageService = chatMessageService;
        this.chatWindowService = chatWindowService;
        this.courseService = courseService;
    }

    /**
     * `API-RAG-001`：SSE 流式问答。
     *
     * <p>建流前的所有校验仍在控制器线程同步执行，失败时由全局异常处理器返回标准 JSON 错误；
     * 一旦返回 {@link SseEmitter}，后续业务异常只通过 `event: error` 传输。
     */
    @PostMapping(value = "/api/v1/rag/qa/stream",
            produces = {MediaType.TEXT_EVENT_STREAM_VALUE, MediaType.APPLICATION_JSON_VALUE})
    public SseEmitter stream(@Valid @RequestBody RagQaRequest body) {
        return ragQaService.stream(SecurityContextUtils.requireCurrentUserId(), body);
    }

    /** `API-CHAT-001`：按 `message_id` 游标分页查询课程会话。 */
    @GetMapping("/api/v1/courses/{courseId}/messages")
    public Result<ChatMessagePageVO> messages(
            @PathVariable("courseId") String courseId,
            @RequestParam(value = "before_message_id", required = false) String beforeMessageId,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        if (limit < 1 || limit > MAX_PAGE_LIMIT) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "limit 必须在 1~" + MAX_PAGE_LIMIT + " 之间。");
        }
        Long userId = SecurityContextUtils.requireCurrentUserId();
        Long parsedCourseId = parseCourseId(courseId);
        courseService.getActiveCourse(userId, parsedCourseId);
        Long before = beforeMessageId == null || beforeMessageId.isBlank()
                ? null : parsePositiveId(beforeMessageId, "before_message_id");
        return Result.ok(chatMessageService.page(userId, parsedCourseId, before, limit));
    }

    /** `API-CHAT-002`：清空当前用户在指定课程下的全部会话与 Redis 窗口。 */
    @DeleteMapping("/api/v1/courses/{courseId}/messages")
    public Result<ClearMessagesVO> clearMessages(@PathVariable("courseId") String courseId) {
        Long userId = SecurityContextUtils.requireCurrentUserId();
        Long parsedCourseId = parseCourseId(courseId);
        courseService.getActiveCourse(userId, parsedCourseId);
        int deleted = chatMessageService.clear(userId, parsedCourseId);
        chatWindowService.clear(userId, parsedCourseId);
        return Result.ok("当前课程会话记录已清空",
                new ClearMessagesVO(String.valueOf(parsedCourseId), deleted));
    }

    private static Long parseCourseId(String raw) {
        return parsePositiveId(raw, "course_id");
    }

    private static Long parsePositiveId(String raw, String field) {
        try {
            long value = Long.parseLong(raw);
            if (value <= 0) {
                throw new NumberFormatException("non-positive");
            }
            return value;
        } catch (NumberFormatException error) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    field + " 必须为正的十进制字符串。");
        }
    }
}
