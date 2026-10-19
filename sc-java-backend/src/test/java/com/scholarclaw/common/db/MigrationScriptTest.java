package com.scholarclaw.common.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

/**
 * 迁移脚本解析与契约守卫（{@code 03} §10.2、{@code 06} TASK-201 验收条件）。
 *
 * <p>其中「V1.0.0 只建 7 张 MVP 表、不得含 v1.1 表」这一条是**验收条件的可执行版本**：
 * TASK-201 明确要求「v1.1 专用表不因预留设计而提前实现其业务功能」，靠人肉 review
 * 守不住，靠断言守得住。
 */
class MigrationScriptTest {

    private static final String REAL_SCRIPT = "db/migration/V1.0.0__init_mvp_schema.sql";

    @Test
    @DisplayName("文件名规范：只接受 V{版本}__{描述}.sql（03 §10.2）")
    void recognizesDocumentedFileNames() {
        assertThat(MigrationScript.isMigrationFileName("V1.0.0__init_mvp_schema.sql")).isTrue();
        assertThat(MigrationScript.isMigrationFileName("V1.1.1__add_index_on_wrong_book.sql")).isTrue();
        assertThat(MigrationScript.isMigrationFileName("V2__short.sql")).isTrue();

        for (String invalid : new String[] {
                null, "", "init.sql", "1.0.0__init.sql", "V1.0.0_init.sql", "V1.0.0__init.sql.bak",
                "U1.0.0__rollback_init.sql"}) {
            assertThat(MigrationScript.isMigrationFileName(invalid))
                    .as("文件名 %s 不应被当作迁移脚本", invalid)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("版本号按数值比较：1.0.0 < 1.0.10 < 1.1.0（不是字符串字典序）")
    void comparesVersionsNumerically() {
        List<String> versions = new ArrayList<>(List.of("1.1.0", "1.0.10", "1.0.0", "1.10.0", "2.0"));

        versions.sort(MigrationScript::compareVersion);

        assertThat(versions).containsExactly("1.0.0", "1.0.10", "1.1.0", "1.10.0", "2.0");
        assertThat(MigrationScript.compareVersion("1.0.0", "1.0.0")).isZero();
        assertThat(MigrationScript.compareVersion("1.0", "1.0.0")).isNegative();
    }

    @Test
    @DisplayName("校验和随内容变化（防漂移的基础），且同一内容稳定")
    void checksumIsStableAndContentSensitive() throws IOException {
        MigrationScript first = scriptOf("V1.0.0__a.sql", "CREATE TABLE t_a (id BIGINT);");
        MigrationScript sameContent = scriptOf("V1.0.0__b.sql", "CREATE TABLE t_a (id BIGINT);");
        MigrationScript changed = scriptOf("V1.0.0__c.sql", "CREATE TABLE t_a (id BIGINT, x INT);");

        assertThat(first.checksum()).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(sameContent.checksum()).isEqualTo(first.checksum());
        assertThat(changed.checksum()).isNotEqualTo(first.checksum());
    }

    @Test
    @DisplayName("可执行 SQL 去掉 CREATE DATABASE / USE，但保留 SET 与全部建表语句")
    void runnableSqlDropsSessionLevelStatements() throws IOException {
        MigrationScript script = MigrationScript.fromResource(new ClassPathResource(REAL_SCRIPT));

        String rawSql = stripComments(script.sql());
        String runnable = stripComments(script.runnableSql());

        assertThat(rawSql).contains("CREATE DATABASE IF NOT EXISTS `scholarclaw`");
        assertThat(rawSql).contains("USE `scholarclaw`;");
        assertThat(runnable).doesNotContain("CREATE DATABASE").doesNotContain("USE `scholarclaw`");
        // SET NAMES / SET time_zone 保留：会话级设置，重复执行无害
        assertThat(runnable).contains("SET NAMES utf8mb4").contains("SET time_zone = '+08:00'");
        assertThat(countOccurrences(runnable, "CREATE TABLE IF NOT EXISTS")).isEqualTo(7);
        assertThat(countOccurrences(runnable, "CREATE TABLE IF NOT EXISTS"))
                .isEqualTo(countOccurrences(rawSql, "CREATE TABLE IF NOT EXISTS"));
    }

    @Test
    @DisplayName("迁移脚本只建 7 张 MVP 表，且不含任何 v1.1 扩展表（06 TASK-201 验收条件）")
    void realScriptCreatesOnlyMvpTables() throws IOException {
        String sql = stripComments(MigrationScript.fromResource(new ClassPathResource(REAL_SCRIPT)).sql());

        assertThat(extractTables(sql)).containsExactly(
                "t_user_config", "t_course_namespace", "t_knowledge_doc", "t_knowledge_parent_chunk",
                "t_ddl_task", "t_chat_message", "t_notification_log");
        // v1.1 的两张表必须留给 V1.1.0 增量脚本（03 §1.4 / DB-04）
        assertThat(sql).doesNotContain("t_ddl_subtask").doesNotContain("t_exam_wrong_book");
        // 03 §9.3：初始化脚本不得插入任何账号/课程种子数据
        assertThat(sql.toUpperCase()).doesNotContain("INSERT INTO").doesNotContain("ADMIN");
    }

    @Test
    @DisplayName("非法文件名或非法版本号直接报错，不做猜测")
    void rejectsInvalidScripts() {
        assertThatThrownBy(() -> MigrationScript.fromResource(
                new ByteArrayResource("SELECT 1".getBytes(), "init.sql")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("03 §10.2");

        assertThatThrownBy(() -> new MigrationScript("v1", "d", "V1.sql", "path", "0".repeat(64), ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("版本号");
        assertThatThrownBy(() -> new MigrationScript("1.0.0", "d", "V1.0.0__a.sql", "path", "not-a-checksum", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("校验和");
    }

    private static MigrationScript scriptOf(String fileName, String sql) throws IOException {
        // ByteArrayResource#getFilename() 恒为 null（它只实现 getDescription），必须匿名覆盖
        return MigrationScript.fromResource(new ByteArrayResource(sql.getBytes()) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });
    }

    /** 断言只针对可执行 SQL：脚本头部的 `--` 注释里会提到 v1.1 表名与 CREATE DATABASE 字样。 */
    private static String stripComments(String sql) {
        return sql.lines()
                .filter(line -> !line.trim().startsWith("--"))
                .reduce("", (left, right) -> left + right + "\n");
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }

    private static List<String> extractTables(String sql) {
        List<String> tables = new ArrayList<>();
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("CREATE TABLE IF NOT EXISTS `(t_\\w+)`").matcher(sql);
        while (matcher.find()) {
            tables.add(matcher.group(1));
        }
        return tables;
    }
}
