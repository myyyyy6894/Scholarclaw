package com.scholarclaw.common.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.scholarclaw.common.db.DatabaseMigrationRunner.AppliedMigration;
import com.scholarclaw.common.db.DatabaseMigrationRunner.MigrationSelection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 迁移选择逻辑断言（不连数据库）。
 *
 * <p>漂移检测与「上次失败必须人工处理」这两条规则一旦写错，后果是不同环境的结构静默分叉 ——
 * 那是靠"再启动一次看看"发现不了的，必须用纯函数测试锁住。
 */
class DatabaseMigrationRunnerTest {

    private static final String CHECKSUM_A = "a".repeat(64);
    private static final String CHECKSUM_B = "b".repeat(64);

    private static MigrationScript script(String version) {
        return new MigrationScript(version, "desc-" + version, "V" + version + ".sql",
                "classpath:V" + version + ".sql", CHECKSUM_A, "SELECT 1;");
    }

    @Test
    @DisplayName("全新库：所有脚本按版本升序待执行")
    void appliesEverythingOnEmptyDatabase() {
        MigrationSelection selection = DatabaseMigrationRunner.selectPending(
                List.of(script("1.0.0"), script("1.0.10"), script("1.1.0")), Map.of());

        assertThat(selection.toApply()).extracting(MigrationScript::version)
                .containsExactly("1.0.0", "1.0.10", "1.1.0");
        assertThat(selection.problems()).isEmpty();
    }

    @Test
    @DisplayName("已成功执行且校验和一致：跳过（重复启动幂等）")
    void skipsAlreadyAppliedScripts() {
        MigrationSelection selection = DatabaseMigrationRunner.selectPending(
                List.of(script("1.0.0"), script("1.1.0")),
                Map.of("1.0.0", new AppliedMigration(CHECKSUM_A, true, null)));

        assertThat(selection.toApply()).extracting(MigrationScript::version).containsExactly("1.1.0");
        assertThat(selection.problems()).isEmpty();
    }

    @Test
    @DisplayName("已执行脚本内容被改动：报漂移并拒绝继续（不静默重跑）")
    void detectsChecksumDrift() {
        MigrationSelection selection = DatabaseMigrationRunner.selectPending(
                List.of(script("1.0.0")),
                Map.of("1.0.0", new AppliedMigration(CHECKSUM_B, true, null)));

        assertThat(selection.toApply()).isEmpty();
        assertThat(selection.problems()).singleElement().asString()
                .contains("校验和不一致").contains("1.0.0");
    }

    @Test
    @DisplayName("上次执行失败：要求人工修复，不自动重试（DDL 无法回滚）")
    void blocksWhenPreviousAttemptFailed() {
        MigrationSelection selection = DatabaseMigrationRunner.selectPending(
                List.of(script("1.0.0"), script("1.1.0")),
                Map.of("1.0.0", new AppliedMigration(CHECKSUM_A, false, "Access denied")));

        assertThat(selection.toApply()).extracting(MigrationScript::version).containsExactly("1.1.0");
        assertThat(selection.problems()).singleElement().asString()
                .contains("上次执行失败").contains("Access denied").contains("人工修复");
    }

    @Test
    @DisplayName("多个问题同时存在时全部上报，便于一次修完")
    void reportsAllProblems() {
        MigrationSelection selection = DatabaseMigrationRunner.selectPending(
                List.of(script("1.0.0"), script("1.0.1"), script("1.1.0")),
                Map.of("1.0.0", new AppliedMigration(CHECKSUM_B, true, null),
                        "1.0.1", new AppliedMigration(CHECKSUM_A, false, "syntax error")));

        assertThat(selection.problems()).hasSize(2);
        assertThat(selection.toApply()).extracting(MigrationScript::version).containsExactly("1.1.0");
    }
}
