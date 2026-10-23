package com.scholarclaw.common.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.common.filter.TraceContext;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
 * Java → Python 内部 AI 调用的**唯一入口**（TASK-303、{@code 02} §7.4.2、{@code 04} §4.9）。
 *
 * <p><b>统一处理四件事：</b>
 * <ol>
 *   <li><b>内部请求头</b>：`X-Internal-Token`、`X-Trace-Id`，以及（有用户身份时）`X-User-Id`；</li>
 *   <li><b>超时</b>：连接超时固定；读取超时可按调用传入（探测 10s、意图/提取 15s 等）；</li>
 *   <li><b>异常映射</b>：Python 信封的 `code` 映射为 Java {@link ErrorCode}，一律使用 Java 的脱敏文案，
 *       **不把 Python 内部异常消息直接透给前端**；</li>
 *   <li><b>安全</b>：显式 {@code followRedirects(NEVER)} 与 {@code ProxySelector.of(null)}；
 *       请求体可能含用户明文 API Key，**任何情况下都不打印请求体**。</li>
 * </ol>
 *
 * <p><b>业务模块不得各自 new HttpClient 或重复拼内部头</b>：新增内部调用时只在这里加一个路径常量 +
 * 调用 {@link #postJson(String, Object, Long, Duration)} 即可。
 */
@Component
public class PythonAiEngineClient {

    private static final Logger log = LoggerFactory.getLogger(PythonAiEngineClient.class);

    /** Python 侧内部探测路径（`04` §4.9.1）。 */
    public static final String PROBE_PATH = "/internal/ai/v1/model/probe";
    /** Python 侧内部向量清理路径（`04` §4.9.4）。 */
    public static final String VECTORS_PATH = "/internal/ai/v1/rag/vectors";
    /** Python 侧内部 RAG 流式问答路径（`04` §4.9.3，TASK-604）。 */
    public static final String RAG_QA_STREAM_PATH = "/internal/ai/v1/rag/qa-stream";
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";
    private static final int MAX_ERROR_BODY_BYTES = 64 * 1024;

    /** 内部调用连接超时上限：超过 5s 会挤压 Java 的 10s 读取预算（`04` §4.9.1）。 */
    static final long MAX_CONNECT_TIMEOUT_MILLIS = 5_000L;

    private final String baseUrl;
    private final String internalToken;
    private final HttpClient httpClient;
    private final Duration defaultReadTimeout;
    private final ObjectMapper objectMapper;

    public PythonAiEngineClient(
            @Value("${sc.internal.python-ai-base-url:}") String baseUrl,
            @Value("${sc.internal.token:}") String internalToken,
            @Value("${sc.model.probe.connect-timeout-millis:5000}") long connectTimeoutMillis,
            @Value("${sc.model.probe.read-timeout-millis:10000}") long readTimeoutMillis,
            ObjectMapper objectMapper) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.internalToken = internalToken;
        this.defaultReadTimeout = Duration.ofMillis(readTimeoutMillis);
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                // 内部服务只支持 HTTP/1.1：JDK 默认的 h2c Upgrade 会被 uvicorn 判为
                // 不支持的升级并丢失请求体，导致内部回调/探测被误判为参数缺失。
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(clampConnectTimeoutMillis(connectTimeoutMillis)))
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(ProxySelector.of(null))
                .build();
    }

    /** 连接超时统一钳制到 `(0, 5s]`，避免配置越界挤压 Java → Python 的读取预算。 */
    static long clampConnectTimeoutMillis(long configuredMillis) {
        if (configuredMillis <= 0) {
            return MAX_CONNECT_TIMEOUT_MILLIS;
        }
        return Math.min(configuredMillis, MAX_CONNECT_TIMEOUT_MILLIS);
    }

    /**
     * 发起内部 JSON POST，返回信封中的 `data` 节点（默认读取超时）。
     *
     * @param userId 已验签的真实用户 ID；无用户身份的内部调用传 {@code null}（不发送 `X-User-Id`）
     * @throws BusinessException Python 不可达/超时/返回非 OK 信封（错误码已映射、文案已脱敏）
     */
    public JsonNode postJson(String path, Object body, Long userId) {
        return postJson(path, body, userId, defaultReadTimeout);
    }

    /** 发起内部 JSON POST，按调用指定读取超时。 */
    public JsonNode postJson(String path, Object body, Long userId, Duration readTimeout) {
        return sendJson("POST", path, body, userId, readTimeout);
    }

    /** 发起内部 JSON DELETE（向量清理等幂等操作）。 */
    public JsonNode deleteJson(String path, Object body, Long userId, Duration readTimeout) {
        return sendJson("DELETE", path, body, userId, readTimeout);
    }

    /**
     * 建立到 Python 的 SSE 长连接（{@code API-INT-AI-003}，TASK-604）。
     *
     * <p>调用方必须在独立线程中读取 :class:`SseStreamHandle`，并在取消/超时时调用
     * :meth:`close` 立即断开下游，Python 会随之取消上游模型请求。
     *
     * @throws BusinessException Python 不可达、非 200 或返回错误信封（错误码已映射、文案已脱敏）
     */
    public SseStreamHandle openSseStream(String path, Object body, Long userId, Duration readTimeout) {
        if (baseUrl.isBlank()) {
            log.error("sc-python-ai 内部基址未配置，无法发起内部流式调用 path={}", path);
            throw new BusinessException(ErrorCode.UPSTREAM_MODEL_ERROR, "AI 引擎地址未配置，请联系管理员。");
        }
        String traceId = TraceContext.currentOrGeneratedTraceId();
        String payload;
        try {
            payload = objectMapper.writeValueAsString(body == null ? Map.of() : body);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "内部请求构造失败。");
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + normalizePath(path)))
                .timeout(readTimeout)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header(INTERNAL_TOKEN_HEADER, internalToken)
                .header(TraceContext.HEADER_NAME, traceId);
        if (userId != null) {
            builder.header(TraceContext.USER_ID_HEADER, String.valueOf(userId));
        }
        HttpRequest request = builder
                .method("POST", HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException e) {
            log.warn("内部流式调用超时 path={} trace_id={}", path, traceId);
            throw new BusinessException(ErrorCode.UPSTREAM_TIMEOUT, ErrorCode.UPSTREAM_TIMEOUT.defaultMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.UPSTREAM_MODEL_ERROR, "内部调用被中断，请稍后重试。");
        } catch (IOException e) {
            log.warn("内部流式调用无法连接 path={} error={}", path, e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.UPSTREAM_MODEL_ERROR,
                    ErrorCode.UPSTREAM_MODEL_ERROR.defaultMessage());
        }

        if (response.statusCode() != 200) {
            String errorBody = readErrorBody(response.body());
            log.warn("内部流式调用失败 path={} status={}", path, response.statusCode());
            throw streamFailure(response.statusCode(), errorBody);
        }
        return new SseLineStream(response.body());
    }

    private String readErrorBody(InputStream body) {
        try (body) {
            byte[] bytes = body.readNBytes(MAX_ERROR_BODY_BYTES);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private BusinessException streamFailure(int status, String body) {
        String code = "";
        try {
            code = objectMapper.readTree(body).path("code").asText("");
        } catch (IOException ignored) {
            // 非信封响应：按状态码兜底
        }
        ErrorCode mapped = ErrorCode.fromCode(code);
        if (mapped == null) {
            mapped = status >= 500 || status == 429
                    ? ErrorCode.UPSTREAM_MODEL_ERROR : ErrorCode.INTERNAL_ERROR;
        }
        if (mapped == ErrorCode.AUTH_FORBIDDEN) {
            mapped = ErrorCode.INTERNAL_ERROR;
        }
        return new BusinessException(mapped, mapped.defaultMessage());
    }

    private JsonNode sendJson(String method, String path, Object body, Long userId,
                              Duration readTimeout) {
        if (baseUrl.isBlank()) {
            log.error("sc-python-ai 内部基址未配置，无法发起内部调用 path={}", path);
            throw new BusinessException(ErrorCode.UPSTREAM_MODEL_ERROR, "AI 引擎地址未配置，请联系管理员。");
        }

        String traceId = TraceContext.currentOrGeneratedTraceId();
        String payload;
        try {
            payload = objectMapper.writeValueAsString(body == null ? Map.of() : body);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "内部请求构造失败。");
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + normalizePath(path)))
                .timeout(readTimeout)
                .header("Content-Type", "application/json")
                .header(INTERNAL_TOKEN_HEADER, internalToken)
                .header(TraceContext.HEADER_NAME, traceId);
        if (userId != null) {
            builder.header(TraceContext.USER_ID_HEADER, String.valueOf(userId));
        }
        HttpRequest request = builder
                .method(method, HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            log.warn("内部调用超时 path={} trace_id={}", path, traceId);
            throw new BusinessException(ErrorCode.UPSTREAM_TIMEOUT, ErrorCode.UPSTREAM_TIMEOUT.defaultMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.UPSTREAM_MODEL_ERROR, "内部调用被中断，请稍后重试。");
        } catch (IOException e) {
            log.warn("内部调用无法连接 path={} error={}", path, e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.UPSTREAM_MODEL_ERROR,
                    ErrorCode.UPSTREAM_MODEL_ERROR.defaultMessage());
        }
        return parseEnvelope(path, response);
    }

    /** 发起内部 JSON POST 并把 `data` 反序列化为指定类型。 */
    public <T> T postJson(String path, Object body, Long userId, Duration readTimeout, Class<T> responseType) {
        JsonNode data = postJson(path, body, userId, readTimeout);
        try {
            return objectMapper.treeToValue(data, responseType);
        } catch (JsonProcessingException e) {
            log.error("内部响应无法映射为 {} path={}", responseType.getSimpleName(), path);
            throw new BusinessException(ErrorCode.UPSTREAM_MODEL_ERROR,
                    ErrorCode.UPSTREAM_MODEL_ERROR.defaultMessage());
        }
    }

    /**
     * 模型连通性/维度探测（{@code API-INT-AI-001}）。
     *
     * <p>探测**不重试**（配置错误要立刻反馈），读取超时使用默认值（10s）。
     *
     * @throws BusinessException 上游不可达/超时/Python 返回非 OK 信封（错误码原样传播、文案已脱敏）
     */
    public ProbeResult probeModel(Long userId, ModelProbeType probeType,
                                  ModelEndpoint chatConfig, ModelEndpoint embeddingConfig) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user_id", String.valueOf(userId));
        body.put("probe_type", probeType.name());
        body.put("chat_config", toPayload(chatConfig));
        body.put("embedding_config", toPayload(embeddingConfig));

        JsonNode data = postJson(PROBE_PATH, body, userId);
        if (data == null || data.isNull() || data.isMissingNode()) {
            throw new BusinessException(ErrorCode.UPSTREAM_MODEL_ERROR,
                    ErrorCode.UPSTREAM_MODEL_ERROR.defaultMessage());
        }
        return new ProbeResult(
                data.path("chat_reachable").asBoolean(false),
                data.path("embedding_reachable").asBoolean(false),
                data.hasNonNull("actual_dim") ? data.get("actual_dim").asInt() : null,
                data.path("latency_ms").asInt(0));
    }

    private JsonNode parseEnvelope(String path, HttpResponse<String> response) {
        JsonNode root;
        try {
            root = objectMapper.readTree(response.body());
        } catch (IOException e) {
            log.error("内部响应不可解析 path={} status={}", path, response.statusCode());
            throw new BusinessException(ErrorCode.UPSTREAM_MODEL_ERROR,
                    ErrorCode.UPSTREAM_MODEL_ERROR.defaultMessage());
        }
        String code = root.path("code").asText("");
        if (!"OK".equals(code)) {
            ErrorCode mapped = ErrorCode.fromCode(code);
            if (mapped == null) {
                mapped = ErrorCode.UPSTREAM_MODEL_ERROR;
            } else if (mapped == ErrorCode.AUTH_FORBIDDEN) {
                // 内部令牌不匹配属部署配置错误，不能给前端展示成“无权访问”
                mapped = ErrorCode.INTERNAL_ERROR;
            }
            log.warn("内部调用失败 path={} code={} status={}", path, code, response.statusCode());
            // 只用 Java 的脱敏文案：Python 的异常消息可能包含字段级细节或内部上下文
            throw new BusinessException(mapped, mapped.defaultMessage());
        }
        JsonNode data = root.path("data");
        return data.isMissingNode() ? objectMapper.nullNode() : data;
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "内部调用路径不能为空。");
        }
        return path.startsWith("/") ? path : "/" + path;
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

    /** 一帧 SSE：`event` 名与原始 `data` JSON 文本。 */
    public record SseFrame(String event, String data) {
    }

    /** SSE 长连接句柄；`next()` 在流结束时返回 {@code null}。 */
    public interface SseStreamHandle extends AutoCloseable {
        SseFrame next() throws IOException;

        @Override
        void close();
    }

    private static final class SseLineStream implements SseStreamHandle {
        private final InputStream raw;
        private final BufferedReader reader;

        private SseLineStream(InputStream raw) {
            this.raw = raw;
            this.reader = new BufferedReader(new InputStreamReader(raw, StandardCharsets.UTF_8));
        }

        @Override
        public SseFrame next() throws IOException {
            String event = null;
            StringBuilder data = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (event != null || data != null) {
                        return new SseFrame(event == null ? "message" : event,
                                data == null ? "" : data.toString());
                    }
                    continue;
                }
                if (line.startsWith(":")) {
                    continue;
                }
                if (line.startsWith("event:")) {
                    event = line.substring("event:".length()).trim();
                } else if (line.startsWith("data:")) {
                    String part = line.substring("data:".length()).trim();
                    if (data == null) {
                        data = new StringBuilder(part);
                    } else {
                        data.append('\n').append(part);
                    }
                }
            }
            return null;
        }

        @Override
        public void close() {
            try {
                raw.close();
            } catch (IOException ignored) {
                // 关闭失败不影响调用方收尾
            }
        }
    }
}
