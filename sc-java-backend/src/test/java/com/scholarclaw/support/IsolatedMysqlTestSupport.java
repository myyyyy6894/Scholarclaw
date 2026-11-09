package com.scholarclaw.support;

import java.sql.DriverManager;
import org.springframework.test.context.DynamicPropertyRegistry;

/** 在加载 Spring 或运行迁移之前校验临时实例身份，避免误连业务库。 */
public final class IsolatedMysqlTestSupport {
    private static final String URL = "jdbc:mysql://127.0.0.1:13306/scholarclaw_unit_test"
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
    private IsolatedMysqlTestSupport() { }

    public static void register(DynamicPropertyRegistry registry) throws Exception {
        String expected = System.getProperty("sc.test.mysql.datadir",
                System.getProperty("sc.qq.test-datadir", ""));
        if (!expected.startsWith("/tmp/scholarclaw-test-mysql.") || !expected.endsWith("/")) {
            throw new IllegalStateException("Explicit temporary MySQL datadir required");
        }
        try (var connection = DriverManager.getConnection(URL, "scholarclaw", "temporary-test-only");
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT @@datadir, DATABASE()")) {
            if (!result.next() || !expected.equals(result.getString(1))
                    || !"scholarclaw_unit_test".equals(result.getString(2))) {
                throw new IllegalStateException("Refusing to run against non-test database");
            }
        }
        registry.add("spring.datasource.url", () -> URL);
        registry.add("spring.datasource.username", () -> "scholarclaw");
        registry.add("spring.datasource.password", () -> "temporary-test-only");
    }
}
