package com.scholarclaw.common.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.config.JacksonConfig;
import com.scholarclaw.common.config.ScTimeZone;
import com.scholarclaw.common.filter.TraceContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Java → Python 内部健康探针的契约断言（`06` TASK-105）。
 *
 * <p>用一个 JDK 内置 {@code HttpServer} 做桩服务，逐条验证探针真正检查的东西：
 * 令牌头是否发出、追踪号是否被原样回显、非 200 / 非 OK / 追踪号不一致 / 重定向 / 响应不可解析
 * 是否都被判为 DOWN。这些正是"内部契约被破坏时能被健康检查立刻发现"的保证。
 */
class PythonAiHealthProbeTest {

    private static final String TOKEN = "test-internal-token-for-probe";

    private final List<String> receivedTokenHeaders = new CopyOnWriteArrayList<>();
    private final List<String> receivedTraceHeaders = new CopyOnWriteArrayList<>();

    private HttpServer server;
    private final ObjectMapper objectMapper =
            JacksonConfig.applyTo(Jackson2ObjectMapperBuilder.json(), ScTimeZone.zoneId()).build();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        TraceContext.clear();
    }

    private PythonAiHealthProbe probeWithHandler(int status, String body) throws IOException {
        return probeWithHandler(status, body, null);
    }

    private PythonAiHealthProbe probeWithHandler(int status, String body, String locationHeader) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(PythonAiHealthProbe.HEALTH_PATH, exchange -> {
            receivedTokenHeaders.add(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            receivedTraceHeaders.add(exchange.getRequestHeaders().getFirst("X-Trace-Id"));
            respond(exchange, status, body, locationHeader);
        });
        server.start();
        return new PythonAiHealthProbe(
                "http://127.0.0.1:" + server.getAddress().getPort(), TOKEN, Duration.ofSeconds(2), objectMapper);
    }

    private static void respond(HttpExchange exchange, int status, String body, String locationHeader) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        if (locationHeader != null) {
            exchange.getResponseHeaders().set("Location", locationHeader);
        }
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    private static String healthyBody(String traceId, String status) {
        return "{\"code\":\"OK\",\"message\":\"Healthy\",\"data\":{\"service\":\"sc-python-ai\","
                + "\"status\":\"" + status + "\",\"dependencies\":{\"shared_storage_ro\":\"UP\"}},"
                + "\"trace_id\":\"" + traceId + "\",\"timestamp\":\"2026-10-18T00:00:00.000+08:00\"}";
    }

    @Test
    @DisplayName("正常路径：探针发送令牌与追踪号，且要求 Python 原样回显追踪号")
    void healthyPeerIsUpAndTraceEchoIsRequired() throws Exception {
        String traceId = "tr_0123456789ab";
        TraceContext.bind(traceId);
        PythonAiHealthProbe probe = probeWithHandler(200, healthyBody(traceId, "UP"));

        assertThat(probe.name()).isEqualTo("python_ai");
        assertThat(probe.isUp()).isTrue();
        assertThat(receivedTokenHeaders).containsExactly(TOKEN);
        assertThat(receivedTraceHeaders).containsExactly(traceId);
    }

    @Test
    @DisplayName("追踪号未被原样回显时判为 DOWN——跨语言追踪链断裂要能被健康检查发现")
    void traceEchoMismatchIsDown() throws Exception {
        TraceContext.bind("tr_0123456789ab");
        PythonAiHealthProbe probe = probeWithHandler(200, healthyBody("tr_ffffffffffff", "UP"));

        assertThat(probe.isUp()).isFalse();
    }

    @Test
    @DisplayName("令牌被拒（403）时判为 DOWN")
    void forbiddenIsDown() throws Exception {
        PythonAiHealthProbe probe = probeWithHandler(403, "{\"code\":\"AUTH_FORBIDDEN\",\"message\":\"无权访问该资源。\"}");

        assertThat(probe.isUp()).isFalse();
    }

    @Test
    @DisplayName("HTTP 200 但自报状态不是 UP 时判为 DOWN")
    void peerReportingDownIsDown() throws Exception {
        String traceId = "tr_0123456789ab";
        TraceContext.bind(traceId);
        PythonAiHealthProbe probe = probeWithHandler(200, healthyBody(traceId, "DOWN"));

        assertThat(probe.isUp()).isFalse();
    }

    @Test
    @DisplayName("重定向不被跟随，直接判为 DOWN（内部调用同样禁止被重定向带偏）")
    void redirectIsDownAndNotFollowed() throws Exception {
        PythonAiHealthProbe probe = probeWithHandler(302, "", "http://169.254.169.254/latest/meta-data/");

        assertThat(probe.isUp()).isFalse();
    }

    @Test
    @DisplayName("响应不是合法信封时判为 DOWN")
    void malformedBodyIsDown() throws Exception {
        PythonAiHealthProbe probe = probeWithHandler(200, "<html>not json</html>");

        assertThat(probe.isUp()).isFalse();
    }

    @Test
    @DisplayName("对端不可达时判为 DOWN，不抛异常")
    void unreachablePeerIsDown() {
        // 127.0.0.1:1 上不会有服务；连接失败必须被吞掉并返回 false
        PythonAiHealthProbe probe = new PythonAiHealthProbe(
                "http://127.0.0.1:1", TOKEN, Duration.ofMillis(300), objectMapper);

        assertThat(probe.isUp()).isFalse();
    }

    @Test
    @DisplayName("无请求上下文时使用 tr_cron_* 追踪号，并同样被要求回显")
    void cronTraceIdIsUsedOutsideRequestContext() throws Exception {
        // 先启动桩服务以得知端口，再构造与回显一致的响应
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(PythonAiHealthProbe.HEALTH_PATH, exchange -> {
            String echo = exchange.getRequestHeaders().getFirst("X-Trace-Id");
            receivedTraceHeaders.add(echo);
            respond(exchange, 200, healthyBody(echo, "UP"), null);
        });
        server.start();
        PythonAiHealthProbe probe = new PythonAiHealthProbe(
                "http://127.0.0.1:" + server.getAddress().getPort(), TOKEN, Duration.ofSeconds(2), objectMapper);

        TraceContext.clear();
        assertThat(probe.isUp()).isTrue();
        assertThat(receivedTraceHeaders.get(0)).matches("^tr_cron_[0-9a-f]{12}$");
    }

    @Test
    @DisplayName("探针角色是 PEER：结果只进 data.peers，不参与容器 status 判定")
    void probeIsAPeerObservationalProbe() throws Exception {
        PythonAiHealthProbe probe = probeWithHandler(200, "{}");

        assertThat(probe.kind()).isEqualTo(HealthProbe.Kind.PEER);
    }

    @Test
    @DisplayName("JVM 配了代理也不影响内部探针：显式直连，避免依赖活着却报 DOWN")
    void probeIgnoresJvmProxyConfiguration() throws Exception {
        String previousHost = System.getProperty("http.proxyHost");
        String previousPort = System.getProperty("http.proxyPort");
        // 指向一个黑洞端口：若探针真的走了代理，就会连接失败并判为 DOWN
        System.setProperty("http.proxyHost", "127.0.0.1");
        System.setProperty("http.proxyPort", "1");
        try {
            String traceId = "tr_0123456789ab";
            TraceContext.bind(traceId);
            PythonAiHealthProbe probe = probeWithHandler(200, healthyBody(traceId, "UP"));

            assertThat(probe.isUp()).isTrue();
        } finally {
            restoreProperty("http.proxyHost", previousHost);
            restoreProperty("http.proxyPort", previousPort);
        }
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
