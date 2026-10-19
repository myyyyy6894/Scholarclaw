package com.scholarclaw.common.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * MySQL 连接池级探针断言（{@code 04} §4.9.8、RISK-25）。
 *
 * <p>重点：**任何**连接异常都必须被吞掉并按 DOWN 上报——探针抛异常会让整个健康接口 500，
 * 那等于"数据库挂了导致健康检查也挂了"，排查信息全部丢失。
 */
class JdbcHealthProbeTest {

    private final DataSource dataSource = mock(DataSource.class);
    private final JdbcHealthProbe probe = new JdbcHealthProbe(dataSource);

    @Test
    @DisplayName("连接有效 → UP；名称与角色固定为 mysql / 自身依赖")
    void reportsUpWhenConnectionIsValid() throws SQLException {
        try (Connection connection = mock(Connection.class)) {
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.isValid(2)).thenReturn(true);

            assertThat(probe.isUp()).isTrue();
        }
        assertThat(probe.name()).isEqualTo("mysql");
        assertThat(probe.kind()).isEqualTo(HealthProbe.Kind.DEPENDENCY);
    }

    @Test
    @DisplayName("连接建立失败（凭据错误/数据库不可达）→ DOWN，不抛异常")
    void reportsDownWhenConnectionCannotBeObtained() throws SQLException {
        when(dataSource.getConnection()).thenThrow(new SQLException("Access denied for user"));

        assertThat(probe.isUp()).isFalse();
    }

    @Test
    @DisplayName("连接失效或运行时异常 → DOWN，不抛异常")
    void reportsDownOnInvalidConnectionOrRuntimeFailure() throws SQLException {
        try (Connection connection = mock(Connection.class)) {
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.isValid(2)).thenReturn(false);
            assertThat(probe.isUp()).isFalse();
        }

        when(dataSource.getConnection()).thenThrow(new IllegalStateException("pool exhausted"));
        assertThat(probe.isUp()).isFalse();
    }
}
