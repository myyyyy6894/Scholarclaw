package com.scholarclaw.modules.notification.controller;

import com.scholarclaw.common.response.Result;
import com.scholarclaw.common.security.SecurityContextUtils;
import com.scholarclaw.modules.notification.dto.MarkNotificationsReadRequest;
import com.scholarclaw.modules.notification.dto.MarkReadResultVO;
import com.scholarclaw.modules.notification.dto.NotificationListVO;
import com.scholarclaw.modules.notification.service.NotificationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 站内通知中心接口（{@code 04} §4.7：`API-NOTIF-001/002`，TASK-802）。
 *
 * <p>身份只来自 JWT；服务层所有查询与更新都强制 {@code user_id} 过滤，
 * 跨用户 ID 无法命中且不会泄露存在性。
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /** `API-NOTIF-001`：分页查询通知列表与全局未读数。 */
    @GetMapping
    public Result<NotificationListVO> list(
            @RequestParam(value = "read_status", required = false) String readStatus,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return Result.ok(notificationService.list(
                SecurityContextUtils.requireCurrentUserId(), readStatus, page, size));
    }

    /** `API-NOTIF-002`：标记单条或全部未读通知为已读（重复调用幂等）。 */
    @PatchMapping("/read")
    public Result<MarkReadResultVO> markRead(
            @Valid @RequestBody MarkNotificationsReadRequest request) {
        return Result.ok("已标记为已读", notificationService.markRead(
                SecurityContextUtils.requireCurrentUserId(),
                request.markAll(), request.notificationIds()));
    }
}
