package com.scholarclaw.modules.ddl.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TASK-704 第 3 层语义防重的确定性规则。 */
class DdlSemanticMatcherTest {

    private final DdlSemanticMatcher matcher = new DdlSemanticMatcher();

    @Test
    @DisplayName("content_hash 对空白/大小写/零宽字符归一化")
    void contentHashNormalizesWhitespaceAndCase() {
        String zeroWidth = String.valueOf((char) 0x200B);
        String first = matcher.contentHash("操作系统原理", "请 于 10月25日 交实验报告");
        String second = matcher.contentHash("操作系统原理", "请于10月25日交实验报告");
        String third = matcher.contentHash("操作系统原理", "请" + zeroWidth + "于10月25日交实验报告");

        assertThat(first).isEqualTo(second).isEqualTo(third);
        assertThat(first).hasSize(64);
    }

    @Test
    @DisplayName("content_hash 对课程名或原文不同则不同")
    void contentHashDependsOnCourseAndText() {
        String base = matcher.contentHash("操作系统原理", "提交实验报告");
        assertThat(matcher.contentHash("数据库系统概念", "提交实验报告")).isNotEqualTo(base);
        assertThat(matcher.contentHash("操作系统原理", "提交实验报告（延期）"))
                .isNotEqualTo(base);
    }

    @Test
    @DisplayName("标题相似：规范化后相等或短标题被长标题包含（长度≥4）")
    void similarTitleRules() {
        assertThat(matcher.isSimilarTitle("提交实验二报告", "提交实验二报告")).isTrue();
        assertThat(matcher.isSimilarTitle("提交实验二报告", "提交实验二报告（延期）")).isTrue();
        assertThat(matcher.isSimilarTitle("提交 实验二报告！", "提交实验二报告")).isTrue();
        assertThat(matcher.isSimilarTitle("完成SQL作业", "提交实验二报告")).isFalse();
        assertThat(matcher.isSimilarTitle("交作业", "交作业延期")).isFalse();
        assertThat(matcher.isSimilarTitle("", "提交实验二报告")).isFalse();
        assertThat(matcher.isSimilarTitle(null, "提交实验二报告")).isFalse();
    }
}
