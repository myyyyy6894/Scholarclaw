package com.scholarclaw.modules.userconfig.controller;

import com.scholarclaw.common.response.Result;
import com.scholarclaw.common.security.SecurityContextUtils;
import com.scholarclaw.modules.userconfig.dto.UserConfigVO;
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前用户配置视图（{@code 04} `API-USER-001`：`GET /api/v1/user/config`）。
 *
 * <p>身份**只**来自 JWT（{@link SecurityContextUtils#requireCurrentUserId()}），
 * 接口不接收任何 `user_id` 参数——即使客户端在 Query/Body 里塞了 `user_id` 也不会被读取
 * （{@code 04} §2.7.2 权限判断铁律）。
 */
@RestController
@RequestMapping("/api/v1/user")
public class UserConfigController {

    private final UserConfigService userConfigService;

    public UserConfigController(UserConfigService userConfigService) {
        this.userConfigService = userConfigService;
    }

    @GetMapping("/config")
    public Result<UserConfigVO> getCurrentUserConfig() {
        Long userId = SecurityContextUtils.requireCurrentUserId();
        UserConfigEntity entity = userConfigService.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_UNAUTHORIZED, "登录状态已失效，请重新登录。"));
        return Result.ok("操作成功", toVO(entity));
    }

    /** `04` §2.5：纯时刻统一输出 `HH:mm:ss`（`LocalTime#toString` 会省略为 `23:30`，不合契约）。 */
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** `04` §2.5：完整时间戳统一带时区偏移量（`yyyy-MM-ddTHH:mm:ss.SSS+08:00`）。 */
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    /** 实体 → VO：只挑选可对外字段，密文与密码哈希永远不出库（{@code 03} §11.2）。 */
    static UserConfigVO toVO(UserConfigEntity entity) {
        return new UserConfigVO(
                entity.getUserId(),
                entity.getUsername(),
                entity.getChatProvider(),
                entity.getChatBaseUrl(),
                entity.getChatApiKeyCipher() != null,
                // 掩码需要解密（TASK-203）；本阶段不存在写入路径，故为 null 而不是伪造掩码
                null,
                entity.getChatModel(),
                entity.getDefaultEmbeddingProvider(),
                entity.getEmbeddingBaseUrl(),
                entity.getEmbeddingApiKeyCipher() != null,
                null,
                entity.getDefaultEmbeddingModel(),
                entity.getDefaultEmbeddingDim(),
                entity.getTimezone(),
                formatTime(entity.getDndStartTime()),
                formatTime(entity.getDndEndTime()),
                entity.getBoundImPlatform(),
                entity.getBoundImUid(),
                formatTimestamp(entity.getUpdatedAt(), entity.getTimezone()));
    }

    private static String formatTime(LocalTime time) {
        return time == null ? null : time.format(TIME_FORMAT);
    }

    /** 按用户自己的时区渲染偏移量（`t_user_config.timezone`，默认 Asia/Shanghai）。 */
    private static String formatTimestamp(java.time.LocalDateTime timestamp, String timezone) {
        if (timestamp == null) {
            return null;
        }
        ZoneId zone = timezone == null || timezone.isBlank() ? ZoneId.of("Asia/Shanghai") : ZoneId.of(timezone);
        return timestamp.atZone(zone).format(TIMESTAMP_FORMAT);
    }
}
