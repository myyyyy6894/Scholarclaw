package com.scholarclaw.modules.channel.controller;

import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.common.response.Result;
import com.scholarclaw.common.security.AesGcmCryptoService;
import com.scholarclaw.common.security.SecurityContextUtils;
import com.scholarclaw.modules.channel.service.QqBotConnectionManager;
import com.scholarclaw.modules.channel.service.QqBotBindingService;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户自带 QQ 官方机器人的连接管理。
 *
 * <p>放在 `channel` 模块而不是 `userconfig`：连接是有状态的长连接，属于通道能力；
 * 凭据写入仍经 {@link UserConfigService}（Single-Writer）。依赖方向是
 * `channel → userconfig`，符合 Strict DAG。
 *
 * <p>AppSecret 只进不出：保存时立即加密，响应里只有 {@code has_qqbot_secret} 布尔值。
 */
@RestController
@RequestMapping("/api/v1/user/qqbot")
public class QqBotController {

    private final QqBotConnectionManager connectionManager;
    private final UserConfigService userConfigService;
    private final AesGcmCryptoService cryptoService;
    private final QqBotBindingService bindingService;

    public QqBotController(QqBotConnectionManager connectionManager,
                           UserConfigService userConfigService,
                           AesGcmCryptoService cryptoService,
                           QqBotBindingService bindingService) {
        this.connectionManager = connectionManager;
        this.userConfigService = userConfigService;
        this.cryptoService = cryptoService;
        this.bindingService = bindingService;
    }

    /** 保存凭据并建立连接；凭据错误会立刻返回可读错误。 */
    @PostMapping("/connect")
    public Result<QqBotConnectVO> connect(@Valid @RequestBody QqBotConnectRequest request) {
        Long userId = SecurityContextUtils.requireCurrentUserId();
        String appId = request.appId().trim();
        String secret = request.clientSecret().trim();
        if (userConfigService.isQqBotAppIdTakenByOther(userId, appId)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "该机器人已被其他账号绑定，请检查 AppID。");
        }
        String cipher;
        try {
            cipher = cryptoService.encrypt(secret);
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "AppSecret 格式不正确（长度或字符超出限制）。");
        }
        // 顺序很关键：先校验凭据 → 再落库 → 最后连接。
        // 反过来会出现"连接时库里还是旧配置"或"凭据错误却覆盖了原配置"。
        connectionManager.validateCredentials(appId, secret);
        connectionManager.configureAndConnect(userId, appId, cipher);
        return Result.ok("机器人连接已启动，请在 Web 获取绑定码后用 QQ 私聊发送。",
                new QqBotConnectVO(appId, "CONNECTING"));
    }

    /** 断开连接并清除凭据。 */
    @PostMapping("/disconnect")
    public Result<Void> disconnect() {
        Long userId = SecurityContextUtils.requireCurrentUserId();
        connectionManager.disconnectAndClear(userId);
        return Result.ok("机器人已断开，凭据已清除。", null);
    }

    /** 绑定码只展示一次；不能被缓存或写入日志。 */
    @PostMapping("/binding-challenge")
    public Result<QqBotChallengeVO> bindingChallenge(HttpServletResponse response) {
        Long userId = SecurityContextUtils.requireCurrentUserId();
        response.setHeader("Cache-Control", "no-store");
        return Result.ok("请在有效期内将绑定码私聊发送给机器人。",
                new QqBotChallengeVO(bindingService.issue(userId), QqBotBindingService.VALID_SECONDS));
    }

    /** 连接请求体。 */
    public record QqBotConnectRequest(
            @NotBlank(message = "app_id 不能为空")
            @Pattern(regexp = "\\d{6,32}", message = "AppID 应为 6~32 位数字")
            String appId,
            @NotBlank(message = "client_secret 不能为空")
            @Size(min = 8, max = 128, message = "AppSecret 长度应为 8~128")
            @Pattern(regexp = "^[\\x21-\\x7E]+$", message = "AppSecret 只能包含可见 ASCII 字符")
            String clientSecret) {
    }

    /** 连接结果（不含任何密钥）。 */
    public record QqBotConnectVO(String appId, String status) {
    }

    public record QqBotChallengeVO(String token, int expiresInSeconds) { }
}
