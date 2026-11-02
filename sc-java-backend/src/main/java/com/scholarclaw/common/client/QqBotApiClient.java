package com.scholarclaw.common.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * QQ 开放平台官方机器人 HTTP 客户端（用户自带 Bot 模式）。
 *
 * <p>只做三件事：取 access_token（带缓存）、取 WebSocket 网关地址、发单聊消息。
 * 连接与事件解析在 {@code channel} 模块完成。
 *
 * <p><b>凭证纪律：</b>AppSecret 只在本类内用于换 token，绝不写日志；
 * token 按 AppID 缓存在内存里，提前 60 秒刷新。
 *
 * <p>接口文档：{@code bot.q.qq.com/wiki/develop/api-v2/}。注意取 token 的参数名是
 * {@code clientSecret}（不是 appSecret），且失败时 HTTP 仍可能是 200，必须看 {@code code}。
 */
@Component
public class QqBotApiClient {

    private static final Logger log = LoggerFactory.getLogger(QqBotApiClient.class);

    private static final String TOKEN_URL = "https://api.bot.qq.com/app/getAppAccessToken";
    private static final String API_BASE = "https://api.sgroup.qq.com";
    private static final String GATEWAY_URL = API_BASE + "/gateway";
    /** token 有效期 7200 秒；提前 60 秒刷新，避免临界点用到过期凭证。 */
    private static final Duration TOKEN_REFRESH_MARGIN = Duration.ofSeconds(60);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Map<String, CachedToken> tokenCache = new ConcurrentHashMap<>();

    public QqBotApiClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /** 机器人侧的可恢复错误：由调用方决定是否重连/重试。 */
    public static class QqBotException extends RuntimeException {
        public QqBotException(String message) {
            super(message);
        }

        public QqBotException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private record CachedToken(String token, Instant expiresAt) {
        boolean usable() {
            return Instant.now().isBefore(expiresAt.minus(TOKEN_REFRESH_MARGIN));
        }
    }

    /**
     * 取 access_token（命中缓存直接返回）。
     *
     * @throws QqBotException AppID/Secret 无效、网络失败或响应缺少 access_token
     */
    public String accessToken(String appId, String clientSecret) {
        CachedToken cached = tokenCache.get(appId);
        if (cached != null && cached.usable()) {
            return cached.token();
        }
        JsonNode body = postJson(TOKEN_URL, Map.of("appId", appId, "clientSecret", clientSecret), null);
        if (body.hasNonNull("code")) {
            // 失败时 HTTP 也是 200，只能看业务码：100016 = AppID 或 Secret 不正确
            throw new QqBotException("获取 access_token 失败（code=" + body.path("code").asText() + "）");
        }
        String token = body.path("access_token").asText("");
        if (token.isBlank()) {
            throw new QqBotException("获取 access_token 失败：响应缺少 access_token");
        }
        long expiresIn = parseExpiresIn(body.path("expires_in"));
        tokenCache.put(appId, new CachedToken(token, Instant.now().plusSeconds(expiresIn)));
        log.info("QQ 机器人 token 已刷新 appId={} 有效期={}s", appId, expiresIn);
        return token;
    }

    /** 使某个机器人的缓存 token 失效（连接被踢/鉴权失败时调用）。 */
    public void invalidateToken(String appId) {
        tokenCache.remove(appId);
    }

    /** 取 WebSocket 网关地址。 */
    public String gatewayUrl(String appId, String clientSecret) {
        String token = accessToken(appId, clientSecret);
        JsonNode body = getJson(GATEWAY_URL, token);
        String url = body.path("url").asText("");
        if (url.isBlank()) {
            throw new QqBotException("获取网关地址失败：" + body);
        }
        return url;
    }

    /**
     * 发送单聊消息。
     *
     * @param msgId  被动回复必须带（用户消息 ID），有效期 60 分钟、每条消息最多 4 次
     * @param msgSeq 同一 msgId 的多次回复靠它区分，递增
     */
    public void sendC2cText(String appId, String clientSecret, String openid,
                            String content, String msgId, int msgSeq) {
        String token = accessToken(appId, clientSecret);
        var payload = new java.util.LinkedHashMap<String, Object>();
        payload.put("content", content);
        payload.put("msg_type", 0);
        if (msgId != null && !msgId.isBlank()) {
            payload.put("msg_id", msgId);
            payload.put("msg_seq", msgSeq);
        }
        JsonNode body = postJson(API_BASE + "/v2/users/" + openid + "/messages", payload, token);
        if (body.hasNonNull("code") && body.path("code").asInt(0) != 0) {
            throw new QqBotException("发送单聊消息失败（code=" + body.path("code").asText() + "）");
        }
    }

    private static long parseExpiresIn(JsonNode node) {
        // 文档示例里 expires_in 是字符串 "7200"，实际可能是数字，两种都要兼容
        String raw = node.isNumber() ? node.asText() : node.asText("");
        try {
            long value = Long.parseLong(raw.trim());
            return value > 0 ? value : 7200;
        } catch (NumberFormatException e) {
            return 7200;
        }
    }

    private JsonNode postJson(String url, Object payload, String token) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(payload), StandardCharsets.UTF_8));
        if (token != null) {
            builder.header("Authorization", "QQBot " + token);
        }
        return send(builder.build());
    }

    private JsonNode getJson(String url, String token) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "QQBot " + token)
                .GET()
                .build();
        return send(request);
    }

    private JsonNode send(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode body = objectMapper.readTree(response.body().isBlank() ? "{}" : response.body());
            if (response.statusCode() >= 400 && !body.hasNonNull("code")) {
                throw new QqBotException("QQ 开放平台返回 HTTP " + response.statusCode());
            }
            return body;
        } catch (IOException e) {
            throw new QqBotException("调用 QQ 开放平台失败：" + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new QqBotException("调用 QQ 开放平台被中断", e);
        }
    }

    private String writeJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (IOException e) {
            throw new QqBotException("序列化请求失败", e);
        }
    }
}
