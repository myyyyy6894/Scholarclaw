package com.scholarclaw.common.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.common.filter.TraceContext;
import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 调用 {@code sc-python-ai} 内部接口的客户端（{@code 02} §7.4.2；本任务只实现模型探测）。
 *
 * <p><b>契约（`04` §4.9 / §4.9.1）：</b>携带 `X-Internal-Token` 与 `X-Trace-Id`；
 * 连接超时 5s、读取超时 10s；探测**不重试**（配置错误要立刻反馈）。
 *
 * <p><b>安全：</b>显式 {@code followRedirects(NEVER)} 与 {@code ProxySelector.of(null)}（内部调用不允许
 * 被重定向带偏、也不继承 JVM 代理配置）；请求体包含用户明文 API Key，因此**任何情况下都不打印请求体**，
 * 错误只记录状态码与错误码。
 *
 * <p>错误传播：Python 返回的信封 `code` 直接映射为 Java 的 {@link ErrorCode}（`04` §6.2：
 * `E-SYS-02`、`UPSTREAM_MODEL_*` 原样上浮，前端看到的错误码两侧一致）。
 */
@Component
public class PythonAiEngineClient {

    private static final Logger log = LoggerFactory.getLogger(PythonAiEngineClient.class);

    /** Python 侧内部探测路径（`04` §4.9.1）。 */
    public static final String PROBE_PATH = "/internal/ai/v1/model/probe";
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    private final String baseUrl;
    private final String internalToken;
    private final HttpClient httpClient;
    private final Duration readTimeout;
    private final ObjectMapper objectMapper;

    public PythonAiEngineClient(
            @Value("${sc.internal.python-ai-base-url:}") String baseUrl,
            @Value("${sc.internal.token:}") String internalToken,
            @Value("${sc.model.probe.connect-timeout-millis:5000}") long connectTimeoutMillis,
            @Value("${sc.model.probe.read-timeout-millis:10000}") long readTimeoutMillis,
            ObjectMapper objectMapper) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.internalToken = internalToken;
        this.readTimeout = Duration.ofMillis(readTimeoutMillis);
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMillis))
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(ProxySelector.of(null))
                .build();
    }

    /**
     * 发起模型连通性/维度探测（`API-INT-AI-001`）。
     *
     * @throws BusinessException 上游不可达/超时/Python 返回非 OK 信封（错误码原样传播）
     */
    public ProbeResult probeModel(Long userId, ModelProbeType probeType,
                                  ModelEndpoint chatConfig, ModelEndpoint embeddingConfig) {
        if (baseUrl.isBlank()) {
            log.error("sc-python-ai 内部基址未配置，无法探测模型");
            throw new BusinessException(ErrorCode.UPSTREAM_MODEL_ERROR, "AI 引擎地址未配置，请联系管理员。");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user_id", String.valueOf(userId));
        body.put("probe_type", probeType.name());
        body.put("chat_config", toPayload(chatConfig));
        body.put("embedding_config", toPayload(embeddingConfig));

        String traceId = TraceContext.currentOrGeneratedTraceId();
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(baseUrl + PROBE_PATH))
                    .timeout(readTimeout)
                    .header("Content-Type", "application/json")
                    .header(INTERNAL_TOKEN_HEADER, internalToken)
                    .header(TraceContext.HEADER_NAME, traceId)
                    // 内部调用携带已验签的真实身份，Python 仅在令牌校验通过后才绑定（04 §6.1.2）
                    .header(TraceContext.USER_ID_HEADER, String.valueOf(userId))
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
        } catch (IOException | IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "模型探测请求构造失败。");
        }

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            log.warn("模型探测超时（trace_id={}）", traceId);
            throw new BusinessException(ErrorCode.UPSTREAM_TIMEOUT, "模型探测超时，请稍后重试。");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.UPSTREAM_MODEL_ERROR, "模型探测被中断，请稍后重试。");
        } catch (IOException e) {
            log.warn("模型探测无法连接 AI 引擎 error={}", e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.UPSTREAM_MODEL_ERROR, "AI 引擎暂时不可用，请稍后重试。");
        }
        return parse(response);
    }

    private ProbeResult parse(HttpResponse<String> response) {
        JsonNode body;
        try {
            body = objectMapper.readTree(response.body());
        } catch (IOException e) {
            log.error("模型探测响应不可解析 status={}", response.statusCode());
            throw new BusinessException(ErrorCode.UPSTREAM_MODEL_ERROR, "AI 引擎返回了无法解析的响应。");
        }
        String code = body.path("code").asText("");
        if (!"OK".equals(code)) {
            ErrorCode mapped = ErrorCode.fromCode(code);
            if (mapped == null) {
                mapped = ErrorCode.UPSTREAM_MODEL_ERROR;
            }
            String message = body.path("message").asText(mapped.defaultMessage());
            log.warn("模型探测失败 code={} status={}", code, response.statusCode());
            throw new BusinessException(mapped, message);
        }
        JsonNode data = body.path("data");
        return new ProbeResult(
                data.path("chat_reachable").asBoolean(false),
                data.path("embedding_reachable").asBoolean(false),
                data.hasNonNull("actual_dim") ? data.get("actual_dim").asInt() : null,
                data.path("latency_ms").asInt(0));
    }

    private static Map<String, Object> toPayload(ModelEndpoint endpoint) {
        if (endpoint == null) {
            return null;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("provider", endpoint.provider());
        payload.put("base_url", endpoint.baseUrl());
        payload.put("api_key", endpoint.apiKey());
        payload.put("model_name", endpoint.modelName());
        return payload;
    }

    /** 探测结果（`04` §4.9.1 响应 `data`）。 */
    public record ProbeResult(boolean chatReachable, boolean embeddingReachable,
                              Integer actualDim, int latencyMs) {
    }
}
