package com.scholarclaw.modules.channel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.client.QqBotApiClient;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.common.security.AesGcmCryptoService;
import com.scholarclaw.common.event.UserDeactivatingEvent;
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

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

    private record ConnectionSlot(UUID token, QqBotConnection connection) { }
    private final Map<Long, ConnectionSlot> connections = new ConcurrentHashMap<>();
    private final Object[] locks = java.util.stream.IntStream.range(0, 64).mapToObj(i -> new Object()).toArray();
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
                synchronized (lockFor(config.getUserId())) {
                    start(config.getUserId());
                }
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
        try {
            apiClient.validateCredentials(appId, clientSecret);
        } catch (QqBotApiClient.QqBotException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "机器人凭据校验失败，请检查 AppID 与 AppSecret 是否正确。");
        }
    }

    /** 串行保存密文并重建连接；不接收明文。 */
    public void configureAndConnect(Long userId, String appId, String cipher) {
        synchronized (lockFor(userId)) {
            UserConfigEntity config = userConfigService.saveQqBotConfig(userId, appId, cipher);
            stop(userId);
            apiClient.invalidateToken(appId);
            userConfigService.updateQqBotStatus(userId, appId, config.getQqbotBindingVersion(), "CONNECTING", null);
            start(userId);
        }
    }

    /** 原子使凭据和连接版本失效，然后停止本进程的连接。 */
    public void disconnectAndClear(Long userId) {
        synchronized (lockFor(userId)) {
            userConfigService.clearQqBotConfig(userId);
            stop(userId);
        }
    }

    /** 当前是否在线（内存态，不查库）。 */
    public boolean isOnline(Long userId) {
        return connections.containsKey(userId);
    }

    private void start(Long userId) {
        UserConfigEntity config = userConfigService.findById(userId).orElse(null);
        if (config == null || !"ACTIVE".equals(config.getAccountStatus())
                || config.getQqbotAppId() == null || config.getQqbotSecretCipher() == null) {
            return;
        }
        String secret;
        try {
            secret = cryptoService.decrypt(config.getQqbotSecretCipher());
        } catch (RuntimeException e) {
            userConfigService.updateQqBotStatus(userId, config.getQqbotAppId(), config.getQqbotBindingVersion(),
                    "ERROR", "凭据解密失败，请重新填写 AppSecret。");
            return;
        }
        UUID token = UUID.randomUUID();
        QqBotConnection connection = new QqBotConnection(
                userId, config.getQqbotAppId(), config.getQqbotBindingVersion(), secret, apiClient, objectMapper,
                message -> inboundExecutor.execute(() -> {
                    if (isCurrent(userId, token)) {
                        channelAdapter.handle(message);
                    }
                }),
                change -> onStatus(change, token, config.getQqbotAppId(), config.getQqbotBindingVersion()));
        ConnectionSlot previous = connections.put(userId, new ConnectionSlot(token, connection));
        if (previous != null) {
            previous.connection().stop();
        }
        connection.start();
        log.info("QQ 机器人连接已启动 userId={}", userId);
    }

    private void stop(Long userId) {
        ConnectionSlot slot = connections.remove(userId);
        if (slot != null) {
            slot.connection().stop();
            log.info("QQ 机器人连接已停止 userId={}", userId);
        }
    }

    private boolean isCurrent(Long userId, UUID token) {
        ConnectionSlot slot = connections.get(userId);
        return slot != null && slot.token().equals(token);
    }

    private Object lockFor(Long userId) {
        return locks[Math.floorMod(userId.hashCode(), locks.length)];
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserDeactivating(UserDeactivatingEvent event) {
        synchronized (lockFor(event.userId())) {
            stop(event.userId());
        }
    }

    @PreDestroy
    public void shutdown() {
        connections.keySet().forEach(this::stop);
        inboundExecutor.shutdownNow();
    }

    private void onStatus(QqBotConnection.StatusChange change, UUID token, String appId, Long version) {
        if (!isCurrent(change.userId(), token)) {
            return;
        }
        String status = change.status();
        String message = change.message();
        if (message != null && message.length() > 200) {
            message = message.substring(0, 200);
        }
        try {
            userConfigService.updateQqBotStatus(change.userId(), appId, version, status, message);
        } catch (RuntimeException e) {
            log.warn("更新机器人连接状态失败 userId={} status={}", change.userId(), status);
        }
    }
}
