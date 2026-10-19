package com.scholarclaw.common.health;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.filter.TraceContext;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 通过**真实内部 HTTP 调用**探测 {@code sc-python-ai}（`06` TASK-105「打通 Java 与 Python 的内部健康检查」）。
 *
 * <p>它不只是 ping 一下端口，而是顺带把两条内部契约验证掉：
 * <ol>
 *   <li><b>{@code X-Internal-Token} 被接受</b>：令牌缺失或错误时 Python 返回 403，探针判为 DOWN；</li>
 *   <li><b>{@code X-Trace-Id} 被原样回显</b>：Java 把当前请求的追踪号发过去，比对 Python 响应体中的
 *       {@code trace_id} 是否一致。不一致说明跨语言追踪链断了，探针判为 DOWN 并告警——
 *       这样契约一旦被后续改动破坏，健康检查会立刻暴露，而不是等到排查线上问题才发现。</li>
 * </ol>
 *
 * <p>实现细节：使用 JDK 自带 {@link HttpClient}（不引入新依赖），并显式
 * {@code followRedirects(NEVER)}——内部调用同样不允许被重定向带偏（`NFR-SEC-05`）。
 *
 * <p>与 TASK-301/303 的 {@code PythonAiEngineClient} 的关系：那个客户端负责模型探测 / 对话 /
 * 向量化等 AI 调用；本探针只做健康检查。若将来希望收敛为单一客户端，可让 TASK-303 把本类
 * 改为走该客户端，响应契约不变。
 *
 * <p><b>角色是 {@link HealthProbe.Kind#PEER}：</b>结果只进响应体的 {@code data.peers}，
 * 不参与 {@code data.status}，避免与 {@code 02} §13.3 的启动依赖链形成环（见 {@link #kind()}）。
 */
public class PythonAiHealthProbe implements HealthProbe {

    private static final Logger log = LoggerFactory.getLogger(PythonAiHealthProbe.class);

    /** Python 侧内部健康检查路径（`04` §4.9.8）。 */
    public static final String HEALTH_PATH = "/internal/ai/v1/health";

    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    private final String baseUrl;
    private final String internalToken;
    private final Duration timeout;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public PythonAiHealthProbe(String baseUrl, String internalToken, Duration timeout, ObjectMapper objectMapper) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.internalToken = internalToken;
        this.timeout = timeout;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                // 显式禁用代理：JDK 默认的 ProxySelector 会读取 -Dhttp.proxyHost 之类的 JVM 参数，
                // 一旦容器/启动脚本设了代理，内部探针就会被代理带偏，表现为"Python 明明活着却报 DOWN"。
                // ProxySelector.of(null) 即"直连"。同类缺陷在 Python 侧已实测到（httpx 默认读代理
                // 环境变量，TASK-105 复核），两侧都显式直连。
                .proxy(ProxySelector.of(null))
                .build();
    }

    @Override
    public String name() {
        return "python_ai";
    }

    /**
     * 跨服务观测项，<b>不参与</b> {@code data.status} 判定（{@link HealthProbe.Kind#PEER}）。
     *
     * <p>若让 Python 的可达性决定 Java 容器的健康，就会与 {@code 02} §13.3 的启动链
     * （`sc-python-ai` 等待 `sc-java-backend` 健康后才启动）构成冷启动死锁；
     * 因此这里的探针结果只作为 {@code data.peers} 上报，供排查与观测使用。
     */
    @Override
    public Kind kind() {
        return Kind.PEER;
    }

    @Override
    public boolean isUp() {
        String traceId = TraceContext.currentOrGeneratedTraceId();
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + HEALTH_PATH))
                .timeout(timeout)
                .header(INTERNAL_TOKEN_HEADER, internalToken)
                .header(TraceContext.HEADER_NAME, traceId)
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                log.warn("sc-python-ai 健康检查返回非 200 status={}（可能是 X-Internal-Token 未通过）", response.statusCode());
                return false;
            }

            JsonNode body = objectMapper.readTree(response.body());
            String code = body.path("code").asText();
            String status = body.path("data").path("status").asText();
            String echoedTraceId = body.path("trace_id").asText();

            if (!traceId.equals(echoedTraceId)) {
                log.warn("内部追踪号未按契约原样回显 expected={} actual={}", traceId, echoedTraceId);
                return false;
            }
            if (!"OK".equals(code) || !"UP".equals(status)) {
                log.warn("sc-python-ai 自报不健康 code={} status={}", code, status);
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("sc-python-ai 健康检查被中断");
            return false;
        } catch (Exception e) {
            // 只记异常类型：异常消息可能包含 URL 或响应片段，避免污染日志
            log.warn("调用 sc-python-ai 健康检查失败 error={}", e.getClass().getSimpleName());
            return false;
        }
    }
}
