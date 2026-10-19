package com.scholarclaw.common.health;

/**
 * 健康检查的单项依赖探针（`04` §4.9.8）。
 *
 * <p><b>为什么做成接口：</b>当前实现是「TCP 可达性」与「真实内部 HTTP 调用」两类；等到
 * TASK-201 引入 JDBC / Redis / RabbitMQ 客户端后，会替换为连接池级探针，
 * 那时只需新增实现类，控制器与响应契约不动。这是一个明确的扩展点，不是提前抽象。
 *
 * <p>实现必须自行吞掉异常并返回 {@code false}：单个依赖探测失败不得让健康检查接口本身报错。
 */
public interface HealthProbe {

    /** 依赖名，直接作为响应体 {@code data.dependencies} / {@code data.peers} 的键。 */
    String name();

    /** 探测结果；异常一律按 {@code false}（DOWN）处理。 */
    boolean isUp();

    /** 探针角色，决定探测结果是否参与 {@code data.status} 判定。 */
    Kind kind();

    /** 探针角色（`04` §4.9.8 与 `02` §13.3 的边界）。 */
    enum Kind {

        /**
         * 自身依赖：`04` §4.9.8 列明的检查对象，任一 DOWN 都让本服务 {@code data.status} 变 DOWN，
         * Compose 据此把容器标记为 unhealthy。
         */
        DEPENDENCY,

        /**
         * 跨服务观测：只上报与对端服务之间的连通性，<b>不参与</b> {@code data.status} 判定。
         *
         * <p>原因是启动依赖链存在方向：`02` §13.3 规定 `sc-python-ai` 必须等
         * `sc-java-backend` 健康后才启动；若 Java 的健康状态又反过来依赖 Python 是否在跑，
         * 冷启动就会死锁（Java 因 Python 未启动而不健康，Python 因 Java 不健康而不启动）。
         * 因此跨服务连通性只上报、不判定，是否可达由 `data.peers` 与两侧容器 healthcheck 体现。
         */
        PEER
    }
}
