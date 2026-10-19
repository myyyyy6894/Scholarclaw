package com.scholarclaw.common.db;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.Resource;

/**
 * 一条数据库迁移脚本（{@code 03} §10.2 命名规范：{@code V{主}.{次}.{修订}__{动词_简述}.sql}）。
 *
 * <p>本类只负责「解析与校验」这些可离线验证的部分（文件名、版本排序、内容校验和），
 * 执行交给 {@link DatabaseMigrationRunner}。刻意做成不可变记录，便于直接单测——
 * 迁移逻辑的正确性不该只能靠"启动一次真库看看"来证明。
 */
public record MigrationScript(String version, String description, String scriptName,
                              String resourcePath, String checksum, String sql) {

    /** `03` §10.2 的文件名规范。 */
    private static final Pattern FILE_NAME =
            Pattern.compile("^V(\\d+(?:\\.\\d+)*)__(.+)\\.sql$");

    /**
     * 会话级语句：{@code CREATE DATABASE} / {@code USE}。
     *
     * <p>脚本是给**手工执行**（root）准备的，所以带这两类语句；Java 迁移器连接的是
     * 已存在的库（JDBC URL 已指定 schema），重复执行它们既无意义，也会在把应用账号
     * 按 {@code 03} §7.4 收敛为 DML-only 之后直接失败。
     */
    private static final Pattern SESSION_LEVEL_STATEMENTS = Pattern.compile(
            "(?im)^\\s*(?:CREATE\\s+DATABASE[^;]*;|USE\\s+`?\\w+`?\\s*;)");

    public MigrationScript {
        if (version == null || !version.matches("\\d+(?:\\.\\d+)*")) {
            throw new IllegalArgumentException("非法迁移版本号：" + version);
        }
        if (checksum == null || !checksum.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("非法迁移校验和：" + checksum);
        }
    }

    /** 文件名是否符合 `V{版本}__{描述}.sql`。 */
    public static boolean isMigrationFileName(String fileName) {
        return fileName != null && FILE_NAME.matcher(fileName).matches();
    }

    /** 解析资源为迁移脚本（读取内容并计算 SHA-256 校验和）。 */
    public static MigrationScript fromResource(Resource resource) throws IOException {
        String fileName = resource.getFilename();
        Matcher matcher = FILE_NAME.matcher(fileName == null ? "" : fileName);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("迁移脚本文件名不符合 03 §10.2 规范：" + fileName);
        }
        String content = readContent(resource);
        return new MigrationScript(
                matcher.group(1), matcher.group(2), matcher.group(0),
                resource.getDescription(), sha256(content), content);
    }

    /**
     * 实际交给 JDBC 执行的 SQL：去掉 `CREATE DATABASE` / `USE` 这两类会话级语句。
     *
     * <p>`SET NAMES` / `SET time_zone` 保留——它们是会话级设置，重复执行无害，
     * 且能保证脚本内 `DATETIME` 字面量按文档时区解释。
     */
    public String runnableSql() {
        return SESSION_LEVEL_STATEMENTS.matcher(sql).replaceAll("");
    }

    /** 按版本号数值比较（1.0.0 &lt; 1.0.10 &lt; 1.1.0，不是字符串字典序）。 */
    public static int compareVersion(String left, String right) {
        String[] a = left.split("\\.");
        String[] b = right.split("\\.");
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            int cmp = Integer.compare(Integer.parseInt(a[i]), Integer.parseInt(b[i]));
            if (cmp != 0) {
                return cmp;
            }
        }
        return Integer.compare(a.length, b.length);
    }

    private static String readContent(Resource resource) throws IOException {
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }
}
