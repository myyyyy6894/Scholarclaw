package com.scholarclaw.common.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.config.JacksonConfig;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.common.filter.TraceContext;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Java → Python 统一内部 AI 客户端（TASK-303、`04` §4.9）。
 *
 * <p>用本机 {@link HttpServer} 做真实 HTTP 往返，验证：
 * 内部请求头（令牌/追踪号/用户）、通用 `postJson`、错误码映射、异常消息脱敏、读取超时分类。
 */
class PythonAiEngineClientTest {

    private static final String TOKEN = "test-internal-token";
    private static final ObjectMapper MAPPER = JacksonConfig
            .applyTo(new Jackson2ObjectMapperBuilder(), ZoneId.of("Asia/Shanghai")).build();

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<Headers> capturedHeaders = new AtomicReference<>();
    private final AtomicReference<String> responseBody = new AtomicReference<>("{}");
    private final AtomicReference<Integer> responseStatus = new AtomicReference<>(200);
    private final AtomicLong delayMillis = new AtomicLong(0);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // 根上下文：探测与未来内部接口共用同一套断言
        server.createContext("/", exchange -> {
            capturedHeaders.set(exchange.getRequestHeaders());
            exchange.getRequestBody().readAllBytes();
            try {
                long delay = delayMillis.get();
                if (delay > 0) {
                    Thread.sleep(delay);
                }
                byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(responseStatus.get(), body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                // 客户端已超时断开时写入会失败；测试只关心客户端侧的 UPSTREAM_TIMEOUT
            } finally {
                exchange.close();
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private PythonAiEngineClient client(String url) {
        return new PythonAiEngineClient(url, TOKEN, 1000, 2000, MAPPER);
    }

    private static ModelEndpoint endpoint() {
        return new ModelEndpoint("DEEPSEEK", "https://api.deepseek.com/v1", "sk-test", "deepseek-chat");
    }

    @Test
    @DisplayName("成功响应解析为 ProbeResult，并携带内部令牌、追踪号与用户 ID")
    void mapsSuccessfulEnvelope() {
        responseBody.set("""
                {"code":"OK","message":"ok","data":{"chat_reachable":true,
                "embedding_reachable":true,"actual_dim":1024,"latency_ms":37}}
                """);

        PythonAiEngineClient.ProbeResult result = client(baseUrl)
                .probeModel(10001L, ModelProbeType.BOTH, endpoint(), endpoint());

        assertThat(result.chatReachable()).isTrue();
        assertThat(result.embeddingReachable()).isTrue();
        assertThat(result.actualDim()).isEqualTo(1024);
        assertThat(result.latencyMs()).isEqualTo(37);

        assertThat(capturedHeaders.get().getFirst("X-Internal-Token")).isEqualTo(TOKEN);
        assertThat(capturedHeaders.get().getFirst("X-Trace-Id")).startsWith("tr_");
        assertThat(capturedHeaders.get().getFirst("X-User-Id")).isEqualTo("10001");
    }

    @Test
    @DisplayName("无请求上下文时使用 tr_cron_* 追踪号，Python 侧必须原样接受并回显")
    void sendsCronTraceIdWhenNoRequestContext() {
        responseBody.set("{\"code\":\"OK\",\"data\":{\"ok\":true}}");
        TraceContext.clear();

        try {
            client(baseUrl).postJson("/internal/ai/v1/agent/route-and-extract",
                    Map.of("raw_text", "hello"), null);
        } finally {
            TraceContext.clear();
        }

        assertThat(capturedHeaders.get().getFirst("X-Trace-Id"))
                .matches("tr_cron_[0-9a-f]{12}");
    }

    @Test
    @DisplayName("通用 postJson 返回 data 节点；无用户身份时不发送 X-User-Id")
    void postJsonIsTheUnifiedEntryAndOmitsUserHeaderWhenAbsent() {
        responseBody.set("{\"code\":\"OK\",\"data\":{\"ok\":true}}");

        JsonNode data = client(baseUrl).postJson("/internal/ai/v1/agent/route-and-extract",
                Map.of("raw_text", "hello"), null);

        assertThat(data.path("ok").asBoolean()).isTrue();
        assertThat(capturedHeaders.get().getFirst("X-Internal-Token")).isEqualTo(TOKEN);
        assertThat(capturedHeaders.get().getFirst("X-Trace-Id")).startsWith("tr_");
        assertThat(capturedHeaders.get().getFirst("X-User-Id")).isNull();
    }

    @Test
    @DisplayName("Python 错误信封的 code 映射为 Java 错误码，且不使用 Python 的原始 message")
    void mapsErrorEnvelopeWithoutExposingPythonMessage() {
        responseStatus.set(400);
        responseBody.set("""
                {"code":"VALIDATION_ERROR","message":"secret detail: column x is null"}
                """);

        assertThatThrownBy(() -> client(baseUrl)
                .probeModel(10001L, ModelProbeType.CHAT, endpoint(), null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException business = (BusinessException) ex;
                    assertThat(business.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(business.getMessage()).isEqualTo(ErrorCode.VALIDATION_ERROR.defaultMessage());
                    assertThat(business.getMessage()).doesNotContain("secret detail");
                });
    }

    @Test
    @DisplayName("内部令牌不匹配（Python AUTH_FORBIDDEN）映射为服务端配置错误，不暴露为前端 403")
    void mapsInternalAuthFailureToInternalError() {
        responseStatus.set(403);
        responseBody.set("{\"code\":\"AUTH_FORBIDDEN\",\"message\":\"internal token mismatch\"}");

        assertThatThrownBy(() -> client(baseUrl)
                .probeModel(10001L, ModelProbeType.CHAT, endpoint(), null))
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INTERNAL_ERROR));
    }

    @Test
    @DisplayName("E-SYS-02 与未知错误码都按契约上浮：已知码保留，未知码归为上游错误")
    void mapsSsrfRejectionAndUnknownCode() {
        responseBody.set("{\"code\":\"E-SYS-02\",\"message\":\"未通过安全校验\"}");
        assertThatThrownBy(() -> client(baseUrl)
                .probeModel(10001L, ModelProbeType.CHAT, endpoint(), null))
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.E_SYS_02));

        responseBody.set("{\"code\":\"SOME_NEW_CODE\",\"message\":\"x\"}");
        assertThatThrownBy(() -> client(baseUrl)
                .probeModel(10001L, ModelProbeType.CHAT, endpoint(), null))
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.UPSTREAM_MODEL_ERROR));
    }

    @Test
    @DisplayName("读取超时映射为 UPSTREAM_TIMEOUT，而不是原始 IO 异常")
    void mapsReadTimeoutToUpstreamTimeout() {
        delayMillis.set(500);
        PythonAiEngineClient slowClient = new PythonAiEngineClient(baseUrl, TOKEN, 1000, 100, MAPPER);

        assertThatThrownBy(() -> slowClient.postJson("/internal/ai/v1/model/probe",
                Map.of("probe_type", "CHAT"), 10001L, Duration.ofMillis(100)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.UPSTREAM_TIMEOUT));
    }

    @Test
    @DisplayName("非 JSON 响应不得被当成功，按上游错误处理")
    void rejectsUnparseableResponse() {
        responseBody.set("<html>bad gateway</html>");

        assertThatThrownBy(() -> client(baseUrl)
                .probeModel(10001L, ModelProbeType.CHAT, endpoint(), null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.UPSTREAM_MODEL_ERROR));
    }

    @Test
    @DisplayName("连接超时统一钳制到 5s 上限，避免越界配置挤压读取预算")
    void clampsConnectTimeout() {
        assertThat(PythonAiEngineClient.clampConnectTimeoutMillis(10_000)).isEqualTo(5_000);
        assertThat(PythonAiEngineClient.clampConnectTimeoutMillis(0)).isEqualTo(5_000);
        assertThat(PythonAiEngineClient.clampConnectTimeoutMillis(1_500)).isEqualTo(1_500);
    }

    @Test
    @DisplayName("未配置 Python 基址时直接失败，不发起请求")
    void failsWhenBaseUrlMissing() {
        assertThatThrownBy(() -> client("")
                .probeModel(10001L, ModelProbeType.CHAT, endpoint(), null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.UPSTREAM_MODEL_ERROR));
    }
}
