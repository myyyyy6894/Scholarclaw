package com.scholarclaw.common.db;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

/**
 * 数据库初始化与迁移执行器（{@code 06} TASK-201、{@code 03} §10）。
 *
 * <p><b>为什么是"有序 SQL 脚本 + 版本记录表"而不是 Flyway</b>：负责人已定稿 P0-03
 * （{@code 03} §10.1 的默认推荐是集成 Flyway，这里是有意偏离并在任务记录中披露）。
 * 本类只做 Flyway 最核心的三件事，不引入额外依赖：
 * <ol>
 *   <li><b>幂等</b>：已成功执行的版本跳过；重复启动不会重复建表；</li>
 *   <li><b>顺序</b>：按版本号数值升序执行（1.0.0 &lt; 1.0.10 &lt; 1.1.0）；</li>
 *   <li><b>防漂移</b>：已执行脚本的内容校验和变了就**拒绝启动**（改历史脚本会让环境间结构悄悄分叉）。</li>
 * </ol>
 *
 * <p><b>失败处理</b>：MySQL 的 DDL 会隐式提交、无法回滚（{@code 03} §10.3），因此失败时
 * 把该版本的 {@code success=0} 与错误摘要落表并**让应用启动失败**（fail-closed）——
 * 半个 schema 上继续跑业务，比启动失败危险得多。人工修复后用
 * {@code DELETE FROM sc_schema_migration WHERE version='x.y.z'} 重新执行。
 *
 * <p><b>开关</b>：{@code sc.db.migration.enabled=false} 可关闭（单元测试与"由运维用特权账号
 * 先手工迁移"的生产流程会用到；此时版本表里的记录会让迁移器无操作）。
 */
@Component
@ConditionalOnProperty(name = "sc.db.migration.enabled", havingValue = "true", matchIfMissing = true)
public class DatabaseMigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseMigrationRunner.class);

    /** 版本记录表（本表自身的结构变更也只能靠人工，故刻意保持极简）。 */
    static final String VERSION_TABLE_DDL = """
            CREATE TABLE IF NOT EXISTS `sc_schema_migration` (
              `version`      VARCHAR(32)  NOT NULL COMMENT '脚本版本号(如 1.0.0)',
              `description`  VARCHAR(255) NOT NULL COMMENT '脚本用途简述',
              `script`       VARCHAR(255) NOT NULL COMMENT '脚本文件名',
              `checksum`     CHAR(64)     NOT NULL COMMENT '脚本内容 SHA-256',
              `success`      TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否执行成功: 0-失败, 1-成功',
              `execution_ms` INT          DEFAULT NULL COMMENT '执行耗时(毫秒)',
              `error_message` VARCHAR(512) DEFAULT NULL COMMENT '失败原因摘要(已截断)',
              `applied_at`   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '执行时间',
              PRIMARY KEY (`version`)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
              COMMENT='数据库迁移版本记录表(03 §10：有序 SQL + 版本表，不引入 Flyway)';
            """;

    private final DataSource dataSource;
    private final String locations;

    public DatabaseMigrationRunner(
            DataSource dataSource,
            @Value("${sc.db.migration.locations:classpath:db/migration}") String locations) {
        this.dataSource = dataSource;
        this.locations = locations;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<MigrationScript> scripts = loadScripts();
        if (scripts.isEmpty()) {
            log.info("数据库迁移：{} 下未找到迁移脚本，跳过", locations);
            return;
        }
        try (Connection connection = dataSource.getConnection()) {
            ensureVersionTable(connection);
            Map<String, AppliedMigration> applied = loadApplied(connection);
            MigrationSelection selection = selectPending(scripts, applied);
            if (!selection.problems().isEmpty()) {
                throw new IllegalStateException("数据库迁移前置校验失败：" + String.join("；", selection.problems()));
            }
            if (selection.toApply().isEmpty()) {
                log.info("数据库迁移：{} 个脚本均已执行（当前版本 {}），无待执行项",
                        scripts.size(), scripts.get(scripts.size() - 1).version());
                return;
            }
            for (MigrationScript script : selection.toApply()) {
                apply(connection, script);
            }
            log.info("数据库迁移完成：本次执行 {} 个脚本，当前版本 {}",
                    selection.toApply().size(), scripts.get(scripts.size() - 1).version());
        } catch (SQLException e) {
            throw new IllegalStateException("数据库迁移无法连接数据库：" + e.getClass().getSimpleName(), e);
        }
    }

    /** 扫描并排序迁移脚本（版本升序）。 */
    List<MigrationScript> loadScripts() {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            Resource[] resources = resolver.getResources(locations + "/V*__*.sql");
            List<MigrationScript> scripts = new ArrayList<>();
            for (Resource resource : resources) {
                if (MigrationScript.isMigrationFileName(resource.getFilename())) {
                    scripts.add(MigrationScript.fromResource(resource));
                }
            }
            scripts.sort((left, right) -> MigrationScript.compareVersion(left.version(), right.version()));
            return scripts;
        } catch (IOException e) {
            throw new IllegalStateException("扫描迁移脚本失败：" + locations, e);
        }
    }

    /**
     * 纯函数：决定这次该执行什么、以及是否存在「必须先人工处理」的问题。
     *
     * <p>单独抽出来是为了能在不连数据库的前提下单测——漂移检测与失败阻断这两条规则
     * 一旦写错，后果是环境间结构静默分叉，必须被测到。
     */
    static MigrationSelection selectPending(List<MigrationScript> scripts,
                                            Map<String, AppliedMigration> applied) {
        List<MigrationScript> toApply = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (MigrationScript script : scripts) {
            AppliedMigration row = applied.get(script.version());
            if (row == null) {
                toApply.add(script);
                continue;
            }
            if (!row.success()) {
                problems.add("版本 " + script.version() + " 上次执行失败（" + row.errorMessage()
                        + "），请人工修复后删除该版本记录再启动");
            } else if (!row.checksum().equals(script.checksum())) {
                problems.add("版本 " + script.version() + " 的脚本内容已变更（校验和不一致），"
                        + "已执行过的迁移脚本不允许修改，请新增增量脚本");
            }
        }
        return new MigrationSelection(List.copyOf(toApply), List.copyOf(problems));
    }

    private void ensureVersionTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(VERSION_TABLE_DDL);
        }
    }

    private Map<String, AppliedMigration> loadApplied(Connection connection) throws SQLException {
        Map<String, AppliedMigration> applied = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT version, checksum, success, error_message FROM sc_schema_migration")) {
            while (rs.next()) {
                applied.put(rs.getString("version"),
                        new AppliedMigration(rs.getString("checksum"), rs.getBoolean("success"),
                                rs.getString("error_message")));
            }
        }
        return applied;
    }

    private void apply(Connection connection, MigrationScript script) {
        long startedAt = System.currentTimeMillis();
        try {
            ScriptUtils.executeSqlScript(connection,
                    new org.springframework.core.io.ByteArrayResource(
                            script.runnableSql().getBytes(StandardCharsets.UTF_8), script.resourcePath()));
            int elapsed = (int) (System.currentTimeMillis() - startedAt);
            recordResult(connection, script, true, elapsed, null);
            log.info("数据库迁移：已执行 V{} {}（{} ms）", script.version(), script.description(), elapsed);
        } catch (RuntimeException e) {
            int elapsed = (int) (System.currentTimeMillis() - startedAt);
            recordResult(connection, script, false, elapsed, e.getMessage());
            throw new IllegalStateException("数据库迁移失败：V" + script.version() + " "
                    + script.description() + "（" + e.getClass().getSimpleName() + "）", e);
        }
    }

    private void recordResult(Connection connection, MigrationScript script, boolean success,
                              int elapsedMillis, String errorMessage) {
        String sql = """
                INSERT INTO sc_schema_migration
                    (version, description, script, checksum, success, execution_ms, error_message)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE description = VALUES(description), script = VALUES(script),
                    checksum = VALUES(checksum), success = VALUES(success),
                    execution_ms = VALUES(execution_ms), error_message = VALUES(error_message),
                    applied_at = CURRENT_TIMESTAMP(3)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, script.version());
            statement.setString(2, truncate(script.description(), 255));
            statement.setString(3, truncate(script.scriptName(), 255));
            statement.setString(4, script.checksum());
            statement.setBoolean(5, success);
            statement.setInt(6, elapsedMillis);
            statement.setString(7, truncate(errorMessage, 512));
            statement.executeUpdate();
        } catch (SQLException e) {
            // 记录失败不能掩盖真正的迁移失败：这里只告警，异常继续向上抛
            log.error("写入迁移版本记录失败 version={} error={}", script.version(), e.getClass().getSimpleName());
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** 已执行的版本记录。 */
    public record AppliedMigration(String checksum, boolean success, String errorMessage) {
    }

    /** 本次迁移的选择结果：待执行脚本 + 需要人工处理的问题。 */
    public record MigrationSelection(List<MigrationScript> toApply, List<String> problems) {
    }
}
