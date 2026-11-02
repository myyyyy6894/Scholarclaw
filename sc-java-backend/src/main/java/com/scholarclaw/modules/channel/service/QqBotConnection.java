package com.scholarclaw.modules.channel.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.client.QqBotApiClient;
import com.scholarclaw.common.client.QqBotApiClient.QqBotException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单个用户机器人的 WebSocket 长连接。
 *
 * <p>一个用户一条连接；连接生命周期由 {@link QqBotConnectionManager} 管理。
 * 协议要点（QQ 开放平台 WebSocket）：
 * <ul>
 *   <li>op=10 Hello 给心跳间隔，随后按间隔发 op=1；</li>
 *   <li>op=0 Dispatch，`t` 是事件名（READY / C2C_MESSAGE_CREATE / GROUP_AT_MESSAGE_CREATE）；</li>
 *   <li>op=7 要求重连，op=9 会话失效（需要重新 Identify 且要刷新 token）。</li>
 * </ul>
 *
 * <p><b>密钥纪律：</b>AppSecret 只在内存里用于换 token，不写日志、不落盘。
 */
public class QqBotConnection {

    private static final Logger log = LoggerFactory.getLogger(QqBotConnection.class);

    /** 单聊与群 @ 消息两个事件位。 */
    static final int INTENTS = (1 << 25) | (1 << 30);

    private static final long[] RECONNECT_DELAYS_SECONDS = {5, 15, 30, 60, 120};

    private final Long userId;
    private final String appId;
    private final String clientSecret;
    private final QqBotApiClient apiClient;
    private final ObjectMapper objectMapper;
    private final Consumer<InboundMessage> inboundHandler;
    private final Consumer<StatusChange> statusListener;
    private final HttpClient httpClient;

    private final ScheduledExecutorService scheduler;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private volatile WebSocket webSocket;
    private volatile ScheduledFuture<?> heartbeatTask;
    private volatile Integer lastSeq;
    private volatile int reconnectAttempt;

    /** 入站消息（已解析出 openid / 内容 / 消息 ID）。 */
    public record InboundMessage(
            Long userId,
            String openid,
            String content,
            String messageId,
            boolean group,
            String groupOpenid) {
    }

    /** 状态变更通知（ONLINE / ERROR + 脱敏原因）。 */
    public record StatusChange(Long userId, String status, String message) {
    }

    public QqBotConnection(Long userId, String appId, String clientSecret,
                           QqBotApiClient apiClient, ObjectMapper objectMapper,
                           Consumer<InboundMessage> inboundHandler,
                           Consumer<StatusChange> statusListener) {
        this.userId = userId;
        this.appId = appId;
        this.clientSecret = clientSecret;
        this.apiClient = apiClient;
        this.objectMapper = objectMapper;
        this.inboundHandler = inboundHandler;
        this.statusListener = statusListener;
        this.httpClient = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build();
        this.scheduler = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "qqbot-" + userId);
            thread.setDaemon(true);
            return thread;
        });
    }

    public Long userId() {
        return userId;
    }

    /** 建立连接（幂等：重复调用只生效一次）。 */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        scheduler.execute(this::connect);
    }

    /** 主动关闭；关闭后不再重连。 */
    public void stop() {
        stopped.set(true);
        cancelHeartbeat();
        WebSocket ws = webSocket;
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").orTimeout(3, TimeUnit.SECONDS);
            } catch (RuntimeException e) {
                ws.abort();
            }
        }
        scheduler.shutdownNow();
    }

    private void connect() {
        if (stopped.get()) {
            return;
        }
        String gateway;
        try {
            gateway = apiClient.gatewayUrl(appId, clientSecret);
        } catch (QqBotException e) {
            statusListener.accept(new StatusChange(userId, "ERROR", e.getMessage()));
            scheduleReconnect("取网关失败：" + e.getMessage());
            return;
        }
        try {
            webSocket = httpClient.newWebSocketBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10))
                    .buildAsync(URI.create(gateway), new Listener())
                    .get(15, TimeUnit.SECONDS);
            log.info("QQ 机器人连接已建立 userId={} appId={}", userId, appId);
        } catch (Exception e) {
            statusListener.accept(new StatusChange(userId, "ERROR", "连接失败：" + e.getClass().getSimpleName()));
            scheduleReconnect("连接失败：" + e.getClass().getSimpleName());
        }
    }

    private void identify() {
        try {
            String token = apiClient.accessToken(appId, clientSecret);
            var payload = objectMapper.createObjectNode();
            payload.put("op", 2);
            var data = payload.putObject("d");
            data.put("token", "QQBot " + token);
            data.put("intents", INTENTS);
            data.putArray("shard").add(0).add(1);
            data.putObject("properties");
            webSocket.sendText(objectMapper.writeValueAsString(payload), true).get(10, TimeUnit.SECONDS);
            log.info("QQ 机器人已发送 Identify userId={}", userId);
        } catch (Exception e) {
            statusListener.accept(new StatusChange(userId, "ERROR", "鉴权失败：" + e.getClass().getSimpleName()));
            reconnectNow("Identify 失败");
        }
    }

    private void cancelHeartbeat() {
        ScheduledFuture<?> task = heartbeatTask;
        if (task != null) {
            task.cancel(true);
            heartbeatTask = null;
        }
    }

    private void scheduleReconnect(String reason) {
        if (stopped.get()) {
            return;
        }
        int index = Math.min(reconnectAttempt, RECONNECT_DELAYS_SECONDS.length - 1);
        long delay = RECONNECT_DELAYS_SECONDS[index];
        reconnectAttempt++;
        log.warn("QQ 机器人将重连 userId={} 原因={} 等待={}s", userId, reason, delay);
        statusListener.accept(new StatusChange(userId, "ERROR", reason));
        scheduler.schedule(this::connect, delay, TimeUnit.SECONDS);
    }

    private void reconnectNow(String reason) {
        cancelHeartbeat();
        WebSocket ws = webSocket;
        if (ws != null) {
            ws.abort();
        }
        scheduleReconnect(reason);
    }

    private void dispatch(JsonNode payload) {
        String event = payload.path("t").asText("");
        JsonNode data = payload.path("d");
        if ("READY".equals(event)) {
            reconnectAttempt = 0;
            statusListener.accept(new StatusChange(userId, "ONLINE", null));
            log.info("QQ 机器人已上线 userId={} bot={}", userId,
                    data.path("user").path("username").asText("-"));
            return;
        }
        if ("C2C_MESSAGE_CREATE".equals(event) || "GROUP_AT_MESSAGE_CREATE".equals(event)) {
            String openid = data.path("author").path("user_openid").asText("");
            String content = data.path("content").asText("");
            String messageId = data.path("id").asText("");
            if (openid.isBlank() || messageId.isBlank()) {
                return;
            }
            boolean group = "GROUP_AT_MESSAGE_CREATE".equals(event);
            String groupOpenid = group ? data.path("group_openid").asText("") : null;
            inboundHandler.accept(new InboundMessage(
                    userId, openid, content.trim(), messageId, group, groupOpenid));
        }
    }

    /** WebSocket 回调；文本帧按累积缓冲处理（平台可能分片下发）。 */
    private final class Listener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String raw = buffer.toString();
                buffer.setLength(0);
                handleFrame(raw);
            }
            webSocket.request(1);
            return null;
        }

        private void handleFrame(String raw) {
            JsonNode payload;
            try {
                payload = objectMapper.readTree(raw);
            } catch (Exception e) {
                return;
            }
            int op = payload.path("op").asInt(-1);
            if (payload.hasNonNull("s")) {
                lastSeq = payload.path("s").asInt();
            }
            switch (op) {
                case 10 -> {
                    // 协议要求：收到 Hello 后先起心跳，再发 Identify
                    startHeartbeat(payload.path("d").path("heartbeat_interval").asLong(30000));
                    identify();
                }
                case 0 -> dispatch(payload);
                case 7 -> reconnectNow("服务端要求重连");
                case 9 -> {
                    // 会话失效：token 可能也失效，清缓存后重连
                    apiClient.invalidateToken(appId);
                    reconnectNow("会话失效");
                }
                default -> {
                    // op=11 心跳 ACK 等无需处理
                }
            }
        }

        private void startHeartbeat(long intervalMillis) {
            cancelHeartbeat();
            long period = Math.max(intervalMillis, 5000);
            heartbeatTask = scheduler.scheduleAtFixedRate(() -> {
                WebSocket ws = webSocket;
                if (ws == null || ws.isOutputClosed()) {
                    return;
                }
                var ping = objectMapper.createObjectNode();
                ping.put("op", 1);
                ping.put("d", lastSeq);
                try {
                    ws.sendText(objectMapper.writeValueAsString(ping), true);
                } catch (Exception e) {
                    reconnectNow("心跳发送失败");
                }
            }, period, period, TimeUnit.MILLISECONDS);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            cancelHeartbeat();
            if (!stopped.get()) {
                scheduleReconnect("连接关闭 code=" + statusCode);
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            cancelHeartbeat();
            if (!stopped.get()) {
                scheduleReconnect("连接异常：" + error.getClass().getSimpleName());
            }
        }
    }
}
