package com.scholarclaw.common.health;

import com.scholarclaw.common.health.HealthProbeRegistry.HealthReport;
import com.scholarclaw.common.response.Result;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 容器内部健康检查接口 {@code GET /internal/api/v1/health}（`04` §4.9.8）。
 *
 * <p>三条与 `04` 保持一致的语义（与 {@code sc-python-ai} 的同名接口完全对齐）：
 * <ol>
 *   <li>响应始终为 HTTP 200 且 {@code code = "OK"}——`04` 第 7 章没有定义"依赖不健康"这类 HTTP
 *       语义；依赖状态体现在 {@code data.status} 与 {@code data.dependencies}。
 *       因此 Compose 的 healthcheck <b>必须解析响应体</b>的 {@code data.status}，
 *       只看 HTTP 状态等于只看进程存活；</li>
 *   <li>{@code data.status} 只由 `04` §4.9.8 列明的自身依赖（MySQL / Redis / RabbitMQ）决定；
 *       与 {@code sc-python-ai} 的连通性单独放在 {@code data.peers}，不参与判定——理由见
 *       {@link HealthProbe.Kind#PEER}（否则与 `02` §13.3 的启动链构成冷启动死锁）；</li>
 *   <li>{@code data} 只返回服务标识、聚合状态与依赖存活状态，**不返回**连接串、主机名或凭据。</li>
 * </ol>
 *
 * <p>鉴权：本路径由 {@code InternalTokenAuthFilter} 保护，并按其环回例外放行容器本机探针
 * （`04` `API-CONF-03`）；例外范围仅限本路径，业务内部接口不适用。
 * 跨容器调用（如 Python 容器 healthcheck）仍需携带 {@code X-Internal-Token}。
 */
@RestController
@RequestMapping("/internal/api/v1")
public class InternalHealthController {

    /** 与 {@code sc-python-ai} 保持一致的接口路径常量。 */
    public static final String HEALTH_PATH = "/internal/api/v1/health";

    private static final Logger log = LoggerFactory.getLogger(InternalHealthController.class);

    private final HealthProbeRegistry probeRegistry;
    private final String serviceName;

    public InternalHealthController(
            HealthProbeRegistry probeRegistry,
            @Value("${spring.application.name:sc-java-backend}") String serviceName) {
        this.probeRegistry = probeRegistry;
        this.serviceName = serviceName;
    }

    @GetMapping("/health")
    public Result<HealthData> health() {
        HealthReport report = probeRegistry.run();
        String status = report.status();
        if (HealthProbeRegistry.STATUS_DOWN.equals(status)) {
            log.warn("健康检查自身依赖不可用 status={} dependencies={}", status, report.dependencies());
        } else if (report.peers().containsValue(HealthProbeRegistry.STATUS_DOWN)) {
            // 跨服务不可达不影响容器健康（见 HealthProbe.Kind.PEER），但必须留痕便于排障
            log.warn("健康检查跨服务观测到不可达 peers={}", report.peers());
        }
        return Result.ok("Healthy", new HealthData(serviceName, status, report.dependencies(), report.peers()));
    }

    /**
     * 响应载荷（`04` §4.9.8）：
     *
     * @param service      服务标识
     * @param status       聚合状态，只由 {@code dependencies} 决定
     * @param dependencies 自身依赖存活状态（参与 {@code status} 判定）
     * @param peers        跨服务连通性观测（不参与 {@code status} 判定），如 {@code python_ai}
     */
    public record HealthData(
            String service,
            String status,
            Map<String, String> dependencies,
            Map<String, String> peers) {
    }
}
