package com.scholarclaw.common.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.config.JacksonConfig;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Java → Python 模型探测客户端（{@code 04} §4.9.1 `API-INT-AI-001`）。
 *
 * <p>用本机 {@link HttpServer} 做真实 HTTP 往返，验证三件事：内部令牌与追踪号确实被带上、
 * Python 信封的 `code` 被原样映射成 Java {@link ErrorCode}、以及不可解析响应被归为上游错误。
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

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/ai/v1/model/probe", exchange -> {
            capturedHeaders.set(exchange.getRequestHeaders());
            exchange.getRequestBody().readAllBytes();
            byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(responseStatus.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
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
    @DisplayName("成功响应解析为 ProbeResult，并携带内部令牌与追踪号")
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
    @DisplayName("Python 错误信封的 code 原样映射为 Java 错误码，不回传上游正文")
    void mapsErrorEnvelopeToSameErrorCode() {
        responseStatus.set(502);
        responseBody.set("""
                {"code":"UPSTREAM_MODEL_AUTH_ERROR","message":"模型服务鉴权失败，请检查 API Key。"}
                """);

        assertThatThrownBy(() -> client(baseUrl)
                .probeModel(10001L, ModelProbeType.CHAT, endpoint(), null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.UPSTREAM_MODEL_AUTH_ERROR));
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
    @DisplayName("未配置 Python 基址时直接失败，不发起请求")
    void failsWhenBaseUrlMissing() {
        assertThatThrownBy(() -> client("")
                .probeModel(10001L, ModelProbeType.CHAT, endpoint(), null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.UPSTREAM_MODEL_ERROR));
    }
}
