package com.scholarclaw.common.health;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MySQL 连接池级健康探针（{@code 04} §4.9.8「检查 MySQL、Redis、RabbitMQ 连接池存活」）。
 *
 * <p><b>它比 TCP 可达性多证明了两件事</b>（RISK-25 的缓解点，TASK-201 引入 JDBC 后落地）：
 * <ol>
 *   <li>凭据是**正确**的——口令错误时 {@code getConnection()} 会失败，TCP 探针发现不了；</li>
 *   <li>连接池**能真正建立可用连接**（含握手、认证、库选择），而不是端口在监听但库不可用。</li>
 * </ol>
 *
 * <p>仍然**不**证明业务表可读写（那需要真实业务 SQL），也不做写操作——健康检查必须是只读的。
 * 使用 {@link Connection#isValid(int)}：MySQL Connector/J 对它做了轻量 ping，不产生业务副作用。
 */
public class JdbcHealthProbe implements HealthProbe {

    private static final Logger log = LoggerFactory.getLogger(JdbcHealthProbe.class);

    /** 验证超时（秒）：必须显著小于 healthcheck 的 5s 超时，否则依赖故障时会挂到被外部杀掉。 */
    private static final int VALIDATION_TIMEOUT_SECONDS = 2;

    private final DataSource dataSource;

    public JdbcHealthProbe(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public String name() {
        return "mysql";
    }

    @Override
    public Kind kind() {
        return Kind.DEPENDENCY;
    }

    @Override
    public boolean isUp() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(VALIDATION_TIMEOUT_SECONDS);
        } catch (SQLException | RuntimeException e) {
            // 只记异常类型：异常消息里可能带 host/端口或凭据片段，不能进日志
            log.warn("MySQL 连接池探针失败 error={}", e.getClass().getSimpleName());
            return false;
        }
    }
}
