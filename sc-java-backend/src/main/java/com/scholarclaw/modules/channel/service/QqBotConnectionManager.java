package com.scholarclaw.modules.channel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.client.QqBotApiClient;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.common.security.AesGcmCryptoService;
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * 用户自带 QQ 机器人的连接管理。
 *
 * <p>一个用户一条 WebSocket；应用启动后按库里的已配置用户自动恢复连接。
 * 状态写回 {@code t_user_config.qqbot_status}，前端据此显示「在线/离线/错误」。
 *
 * <p>入站消息在独立线程池处理（模型调用可能几十秒），与 WebSocket 读线程解耦，
 * 队列满时由 CallerRuns 兜底——**绝不丢消息**，宁可拖慢该条连接。
 */
@Service
public class QqBotConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(QqBotConnectionManager.class);

    private final UserConfigService userConfigService;
    private final AesGcmCryptoService cryptoService;
    private final QqBotApiClient apiClient;
    private final ObjectMapper objectMapper;
    private final QqBotChannelAdapter channelAdapter;

    private final Map<Long, QqBotConnection> connections = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor inboundExecutor;

    public QqBotConnectionManager(UserConfigService userConfigService,
                                  AesGcmCryptoService cryptoService,
                                  QqBotApiClient apiClient,
                                  ObjectMapper objectMapper,
                                  QqBotChannelAdapter channelAdapter) {
        this.userConfigService = userConfigService;
        this.cryptoService = cryptoService;
        this.apiClient = apiClient;
        this.objectMapper = objectMapper;
        this.channelAdapter = channelAdapter;
        AtomicInteger seq = new AtomicInteger();
        this.inboundExecutor = new ThreadPoolExecutor(
                2, 4, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(200),
                runnable -> {
                    Thread thread = new Thread(runnable, "qqbot-inbound-" + seq.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /** 应用启动完成后恢复所有已配置机器人的连接。 */
    @EventListener(ApplicationReadyEvent.class)
    public void restoreOnStartup() {
        for (UserConfigEntity config : userConfigService.findQqBotConfigured()) {
            try {
                start(config.getUserId());
            } catch (RuntimeException e) {
                log.warn("启动恢复机器人连接失败 userId={} error={}",
                        config.getUserId(), e.getClass().getSimpleName());
            }
        }
    }

    /**
     * 校验 AppID/AppSecret 是否可用（真实换一次 token）。
     *
     * <p>必须在**保存凭据之前**调用：凭据错误时不能覆盖用户原有的可用配置。
     */
    public void validateCredentials(String appId, String clientSecret) {
        apiClient.invalidateToken(appId);
        try {
            apiClient.accessToken(appId, clientSecret);
        } catch (QqBotApiClient.QqBotException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "机器人凭据校验失败，请检查 AppID 与 AppSecret 是否正确。");
        }
    }

    /**
     * 用**已保存**的凭据建立（或重建）连接。
     *
     * <p>调用方必须先把凭据落库——本方法从 `t_user_config` 读取，不接收明文。
     */
    public void connect(Long userId) {
        stop(userId);
        userConfigService.updateQqBotStatus(userId, "CONNECTING", null);
        start(userId);
    }

    /** 断开并停止重连（不清库里的凭据）。 */
    public void disconnect(Long userId) {
        stop(userId);
        userConfigService.updateQqBotStatus(userId, "DISCONNECTED", null);
    }

    /** 当前是否在线（内存态，不查库）。 */
    public boolean isOnline(Long userId) {
        QqBotConnection connection = connections.get(userId);
        return connection != null;
    }

    private void start(Long userId) {
        UserConfigEntity config = userConfigService.findById(userId).orElse(null);
        if (config == null || config.getQqbotAppId() == null || config.getQqbotSecretCipher() == null) {
            return;
        }
        String secret;
        try {
            secret = cryptoService.decrypt(config.getQqbotSecretCipher());
        } catch (RuntimeException e) {
            userConfigService.updateQqBotStatus(userId, "ERROR", "凭据解密失败，请重新填写 AppSecret。");
            return;
        }
        QqBotConnection connection = new QqBotConnection(
                userId, config.getQqbotAppId(), secret, apiClient, objectMapper,
                message -> inboundExecutor.execute(() -> channelAdapter.handle(message)),
                this::onStatus);
        connections.put(userId, connection);
        connection.start();
        log.info("QQ 机器人连接已启动 userId={}", userId);
    }

    private void stop(Long userId) {
        QqBotConnection connection = connections.remove(userId);
        if (connection != null) {
            connection.stop();
            log.info("QQ 机器人连接已停止 userId={}", userId);
        }
    }

    private void onStatus(QqBotConnection.StatusChange change) {
        String status = change.status();
        String message = change.message();
        if (message != null && message.length() > 200) {
            message = message.substring(0, 200);
        }
        try {
            userConfigService.updateQqBotStatus(change.userId(), status, message);
        } catch (RuntimeException e) {
            log.warn("更新机器人连接状态失败 userId={} status={}", change.userId(), status);
        }
    }
}
