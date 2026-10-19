package com.scholarclaw.common.health;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 依赖探针注册表：执行全部探针，并按角色分成「自身依赖」与「跨服务观测」两组。
 *
 * <p>构造参数是显式的 {@code List<HealthProbe>}，由 {@link HealthProbesConfig} 按配置装配，
 * 使本类可以在不启动 Spring 上下文的情况下直接单测。
 *
 * <p><b>分组的意义（`02` §13.3）：</b>只有 {@link HealthProbe.Kind#DEPENDENCY} 参与
 * {@code data.status} 判定；{@link HealthProbe.Kind#PEER} 的连通性结果单独放在
 * {@code data.peers}。这样 Compose 的 healthcheck 可以放心按 {@code data.status} 判定容器健康，
 * 而不会把跨服务连通性卷入启动依赖链造成环。
 *
 * <p>状态取值与 {@code sc-python-ai} 保持完全一致（{@code UP} / {@code DOWN}），
 * 避免两侧对同一个健康语义给出不同词表。
 */
public class HealthProbeRegistry {

    private static final Logger log = LoggerFactory.getLogger(HealthProbeRegistry.class);

    public static final String STATUS_UP = "UP";
    public static final String STATUS_DOWN = "DOWN";

    private final List<HealthProbe> probes;

    public HealthProbeRegistry(List<HealthProbe> probes) {
        this.probes = List.copyOf(probes);
    }

    /** 执行全部探针并按角色分组；探针自身异常按 DOWN 处理且不影响其他探针。 */
    public HealthReport run() {
        Map<String, String> dependencies = new LinkedHashMap<>();
        Map<String, String> peers = new LinkedHashMap<>();
        for (HealthProbe probe : probes) {
            String result = probeResult(probe);
            if (probe.kind() == HealthProbe.Kind.PEER) {
                peers.put(probe.name(), result);
            } else {
                dependencies.put(probe.name(), result);
            }
        }
        return new HealthReport(dependencies, peers);
    }

    private String probeResult(HealthProbe probe) {
        try {
            return probe.isUp() ? STATUS_UP : STATUS_DOWN;
        } catch (RuntimeException e) {
            log.warn("依赖探针执行异常 dependency={} error={}", probe.name(), e.getClass().getSimpleName());
            return STATUS_DOWN;
        }
    }

    /**
     * 聚合状态：只看自身依赖，全部 UP 才是 UP；没有自身依赖时视为 UP（服务自身可用）。
     *
     * <p>跨服务观测项（{@code peers}）<b>不传入</b>本方法——那不是本服务的可用性判据。
     */
    public static String overallStatus(Map<String, String> dependencies) {
        if (dependencies.isEmpty()) {
            return STATUS_UP;
        }
        return dependencies.values().stream().allMatch(STATUS_UP::equals) ? STATUS_UP : STATUS_DOWN;
    }

    /** 一次健康检查的执行结果：自身依赖（参与 status 判定）与跨服务观测（不参与）。 */
    public record HealthReport(Map<String, String> dependencies, Map<String, String> peers) {

        /** 本服务的聚合状态，由自身依赖决定。 */
        public String status() {
            return overallStatus(dependencies);
        }
    }
}
