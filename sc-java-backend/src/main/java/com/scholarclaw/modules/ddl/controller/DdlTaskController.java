package com.scholarclaw.modules.ddl.controller;

import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.common.response.Result;
import com.scholarclaw.common.security.SecurityContextUtils;
import com.scholarclaw.modules.ddl.dto.CompleteDdlTaskRequest;
import com.scholarclaw.modules.ddl.dto.CreateDdlTaskRequest;
import com.scholarclaw.modules.ddl.dto.DdlExtractVO;
import com.scholarclaw.modules.ddl.dto.DdlTaskBoardVO;
import com.scholarclaw.modules.ddl.dto.DdlTaskVO;
import com.scholarclaw.modules.ddl.dto.DeleteDdlTaskVO;
import com.scholarclaw.modules.ddl.dto.DraftDecisionRequest;
import com.scholarclaw.modules.ddl.dto.ExtractDdlTaskRequest;
import com.scholarclaw.modules.ddl.dto.UpdateDdlTaskRequest;
import com.scholarclaw.modules.ddl.service.DdlExtractionService;
import com.scholarclaw.modules.ddl.service.DdlTaskService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * DDL 任务接口（{@code 04} §4.6：`API-DDL-001~004/005~007`，TASK-703/704）。
 *
 * <p>身份只来自 JWT；路径中的 {@code task_id} 仅作为资源标识，服务层始终用
 * {@code user_id + task_id} 联合过滤，跨用户访问统一返回 404（防 IDOR）。
 */
@RestController
@RequestMapping("/api/v1/ddl/tasks")
public class DdlTaskController {

    private final DdlTaskService ddlTaskService;
    private final DdlExtractionService ddlExtractionService;

    public DdlTaskController(DdlTaskService ddlTaskService,
                             DdlExtractionService ddlExtractionService) {
        this.ddlTaskService = ddlTaskService;
        this.ddlExtractionService = ddlExtractionService;
    }

    /** `API-DDL-001`：粘贴通知文本提取并创建任务 / 返回语义重复提示或变更草案。 */
    @PostMapping("/extract")
    public Result<DdlExtractVO> extract(@Valid @RequestBody ExtractDdlTaskRequest request) {
        return Result.ok("DDL 提取处理完成",
                ddlExtractionService.extract(
                        SecurityContextUtils.requireCurrentUserId(), request));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Result<DdlTaskVO> create(@Valid @RequestBody CreateDdlTaskRequest request) {
        return Result.ok("DDL 任务创建成功",
                ddlTaskService.createManualTask(
                        SecurityContextUtils.requireCurrentUserId(), request));
    }

    @GetMapping
    public Result<DdlTaskBoardVO> list(
            @RequestParam(value = "status_group", required = false) String statusGroup,
            @RequestParam(value = "course_id", required = false) String courseId) {
        return Result.ok(ddlTaskService.listTasks(
                SecurityContextUtils.requireCurrentUserId(), statusGroup, courseId));
    }

    @PutMapping("/{taskId}")
    public Result<DdlTaskVO> update(@PathVariable("taskId") String taskId,
                                    @Valid @RequestBody UpdateDdlTaskRequest request) {
        return Result.ok("DDL 任务已更新",
                ddlTaskService.updateTask(
                        SecurityContextUtils.requireCurrentUserId(), parseTaskId(taskId), request));
    }

    @PatchMapping("/{taskId}/completion")
    public Result<DdlTaskVO> setCompletion(@PathVariable("taskId") String taskId,
                                           @Valid @RequestBody CompleteDdlTaskRequest request) {
        return Result.ok(request.completed()
                        ? "DDL 任务已标记完成"
                        : "DDL 任务已取消完成",
                ddlTaskService.setCompletion(
                        SecurityContextUtils.requireCurrentUserId(), parseTaskId(taskId),
                        Boolean.TRUE.equals(request.completed())));
    }

    @DeleteMapping("/{taskId}")
    public Result<DeleteDdlTaskVO> delete(@PathVariable("taskId") String taskId) {
        return Result.ok("任务已删除",
                ddlTaskService.cancelTask(
                        SecurityContextUtils.requireCurrentUserId(), parseTaskId(taskId)));
    }

    /** `API-DDL-005`：确认更新 / 仍然新建 / 忽略重复通知变更草案。 */
    @PostMapping("/{taskId}/draft-decision")
    public Result<DdlTaskVO> decideDraft(@PathVariable("taskId") String taskId,
                                         @Valid @RequestBody DraftDecisionRequest request) {
        return Result.ok("变更草案处理完成",
                ddlExtractionService.decideDraft(
                        SecurityContextUtils.requireCurrentUserId(), parseTaskId(taskId),
                        request));
    }

    private static Long parseTaskId(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 19) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "task_id 必须为正的十进制字符串。");
        }
        try {
            long value = Long.parseLong(raw);
            if (value <= 0) {
                throw new NumberFormatException("non-positive");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "task_id 必须为正的十进制字符串。");
        }
    }
}
