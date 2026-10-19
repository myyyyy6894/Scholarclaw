package com.scholarclaw.common.health;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 按配置装配依赖探针。
 *
 * <p>策略：**配置了主机才注册对应探针**。例如本机单跑 Java、未注入 {@code SC_DB_HOST} 时，
 * 不会去探测 MySQL 而误报 DOWN；在 Compose 里这些变量由编排注入，探针自然全部生效。
 *
 * <p>新增配置键（`02` §13.2 的注入表未列，属本任务引入，已在交付报告中披露）：
 * {@code SC_PYTHON_AI_BASE_URL}、{@code SC_DEPENDENCY_PROBE_TIMEOUT_MILLIS}。
 */
@Configuration
public class HealthProbesConfig {

    @Bean
    public HealthProbeRegistry healthProbeRegistry(
            @Value("${sc.dependencies.probe-timeout-millis:1500}") int probeTimeoutMillis,
            @Value("${sc.dependencies.mysql.host:}") String mysqlHost,
            @Value("${sc.dependencies.mysql.port:3306}") int mysqlPort,
            @Value("${sc.dependencies.mysql.pool-probe:true}") boolean mysqlPoolProbe,
            ObjectProvider<DataSource> dataSourceProvider,
            @Value("${sc.dependencies.redis.host:}") String redisHost,
            @Value("${sc.dependencies.redis.port:6379}") int redisPort,
            @Value("${sc.dependencies.redis.pool-probe:true}") boolean redisPoolProbe,
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            @Value("${sc.dependencies.rabbitmq.host:}") String rabbitmqHost,
            @Value("${sc.dependencies.rabbitmq.port:5672}") int rabbitmqPort,
            @Value("${sc.internal.python-ai-base-url:}") String pythonAiBaseUrl,
            @Value("${sc.internal.token:}") String internalToken,
            ObjectMapper objectMapper) {

        List<HealthProbe> probes = new ArrayList<>();
        // mysql 优先连接池级探针（TASK-201 引入 JDBC 后）：能证明凭据正确、连接池可用，
        // 而 TCP 探针只能证明"端口在监听"。没有 DataSource 时回退到 TCP 探针。
        DataSource dataSource = mysqlPoolProbe ? dataSourceProvider.getIfAvailable() : null;
        if (dataSource != null) {
            probes.add(new JdbcHealthProbe(dataSource));
        } else {
            addTcpProbe(probes, "mysql", mysqlHost, mysqlPort, probeTimeoutMillis);
        }
        StringRedisTemplate redisTemplate = redisPoolProbe ? redisTemplateProvider.getIfAvailable() : null;
        if (redisTemplate != null) {
            probes.add(new RedisHealthProbe(redisTemplate));
        } else {
            addTcpProbe(probes, "redis", redisHost, redisPort, probeTimeoutMillis);
        }
        addTcpProbe(probes, "rabbitmq", rabbitmqHost, rabbitmqPort, probeTimeoutMillis);

        if (!pythonAiBaseUrl.isBlank()) {
            probes.add(new PythonAiHealthProbe(
                    pythonAiBaseUrl.trim(), internalToken, Duration.ofSeconds(5), objectMapper));
        }
        return new HealthProbeRegistry(probes);
    }

    private static void addTcpProbe(List<HealthProbe> probes, String name, String host, int port, int timeoutMillis) {
        if (!host.isBlank()) {
            probes.add(new TcpReachabilityProbe(name, host.trim(), port, timeoutMillis));
        }
    }
}
