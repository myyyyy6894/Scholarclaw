package com.scholarclaw.common.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.scholarclaw.common.health.HealthProbe.Kind;
import com.scholarclaw.common.health.HealthProbeRegistry.HealthReport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 依赖探针注册表断言（`04` §4.9.8、`02` §13.3）。
 *
 * <p>重点两条：
 * <ul>
 *   <li>单个探针失败或被探测的依赖不可用，都不得影响其它探针，也不得让健康接口本身报错；</li>
 *   <li><b>跨服务观测项（{@code PEER}）不得进入 {@code status} 判定</b>——否则 Java 的健康
 *       状态会依赖 Python 是否在跑，与 `02` §13.3「Python 等 Java 健康后才启动」构成冷启动死锁。</li>
 * </ul>
 */
class HealthProbeRegistryTest {

    /** 测试替身：只用于测试，不进入生产类路径。 */
    private record StaticProbe(String name, boolean up, Kind kind) implements HealthProbe {
        @Override
        public boolean isUp() {
            return up;
        }
    }

    private record ExplodingProbe(String name, Kind kind) implements HealthProbe {
        @Override
        public boolean isUp() {
            throw new IllegalStateException("探测内部异常");
        }
    }

    private static HealthProbe dependency(String name, boolean up) {
        return new StaticProbe(name, up, Kind.DEPENDENCY);
    }

    private static HealthProbe peer(String name, boolean up) {
        return new StaticProbe(name, up, Kind.PEER);
    }

    @Test
    @DisplayName("全部自身依赖 UP 时聚合状态为 UP")
    void allUpYieldsUp() {
        HealthProbeRegistry registry = new HealthProbeRegistry(List.of(
                dependency("mysql", true), dependency("redis", true)));

        HealthReport report = registry.run();

        assertThat(report.dependencies()).containsExactly(
                Map.entry("mysql", "UP"), Map.entry("redis", "UP"));
        assertThat(report.status()).isEqualTo("UP");
    }

    @Test
    @DisplayName("任一自身依赖 DOWN 时聚合状态为 DOWN，且其他探针仍被正常执行")
    void anyDownYieldsDownAndOthersStillRun() {
        HealthProbeRegistry registry = new HealthProbeRegistry(List.of(
                dependency("mysql", false), dependency("redis", true)));

        HealthReport report = registry.run();

        assertThat(report.dependencies()).containsEntry("mysql", "DOWN").containsEntry("redis", "UP");
        assertThat(report.status()).isEqualTo("DOWN");
    }

    @Test
    @DisplayName("探针自身抛异常按 DOWN 处理，不向上抛")
    void explodingProbeIsReportedAsDown() {
        HealthProbeRegistry registry = new HealthProbeRegistry(List.of(
                new ExplodingProbe("mysql", Kind.DEPENDENCY), dependency("redis", true)));

        HealthReport report = registry.run();

        assertThat(report.dependencies()).containsEntry("mysql", "DOWN").containsEntry("redis", "UP");
    }

    @Test
    @DisplayName("无任何探针时视为 UP（服务自身可用）")
    void emptyRegistryIsUp() {
        assertThat(new HealthProbeRegistry(List.of()).run().status()).isEqualTo("UP");
    }

    @Test
    @DisplayName("跨服务观测项 DOWN 不影响本服务 status，只出现在 peers 中")
    void peerDownDoesNotAffectStatus() {
        HealthProbeRegistry registry = new HealthProbeRegistry(List.of(
                dependency("mysql", true), peer("python_ai", false)));

        HealthReport report = registry.run();

        assertThat(report.dependencies()).containsExactly(Map.entry("mysql", "UP"));
        assertThat(report.peers()).containsExactly(Map.entry("python_ai", "DOWN"));
        assertThat(report.status())
                .as("Python 不可达不得让 Java 容器变 unhealthy，否则冷启动死锁")
                .isEqualTo("UP");
    }

    @Test
    @DisplayName("跨服务观测项抛异常同样按 DOWN 上报，且不影响 status")
    void explodingPeerProbeIsDownButStatusStaysUp() {
        HealthProbeRegistry registry = new HealthProbeRegistry(List.of(
                dependency("mysql", true), new ExplodingProbe("python_ai", Kind.PEER)));

        HealthReport report = registry.run();

        assertThat(report.peers()).containsEntry("python_ai", "DOWN");
        assertThat(report.status()).isEqualTo("UP");
    }

    @Test
    @DisplayName("两类探针按角色分组，互不串位")
    void probesAreGroupedByKind() {
        HealthProbeRegistry registry = new HealthProbeRegistry(List.of(
                dependency("mysql", true),
                dependency("redis", true),
                dependency("rabbitmq", true),
                peer("python_ai", true)));

        HealthReport report = registry.run();

        assertThat(report.dependencies()).containsOnlyKeys("mysql", "redis", "rabbitmq");
        assertThat(report.peers()).containsOnlyKeys("python_ai");
        assertThat(report.status()).isEqualTo("UP");
    }

    @Test
    @DisplayName("overallStatus 只接受自身依赖映射，空映射视为 UP")
    void overallStatusOnlyConsidersDependencies() {
        assertThat(HealthProbeRegistry.overallStatus(Map.of())).isEqualTo("UP");
        assertThat(HealthProbeRegistry.overallStatus(Map.of("mysql", "UP", "redis", "UP"))).isEqualTo("UP");
        assertThat(HealthProbeRegistry.overallStatus(Map.of("mysql", "UP", "redis", "DOWN"))).isEqualTo("DOWN");
    }
}
