package com.scholarclaw.modules.ddl.service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * DDL 第 3 层业务语义防重（{@code 03} §4.5.1、{@code 04} API-DDL-001）。
 *
 * <p>两层判定：
 * <ul>
 *   <li>{@code content_hash}：规范化课程名 + 原文的 SHA-256，用于近 14 天完全相同通知；</li>
 *   <li>标题相似度：规范化后相等，或较短标题（长度 ≥4）被较长标题包含；用于同课程相似任务
 *       的变更草案检测。</li>
 * </ul>
 *
 * <p>这是**确定性启发式**，不是语义模型；无法判断时宁可返回“不相似”而插入新任务，
 * 也不静默覆盖已有任务。
 */
@Component
public class DdlSemanticMatcher {

    private static final Pattern WHITESPACE =
            Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern ZERO_WIDTH = Pattern.compile("[\\u200B-\\u200D\\uFEFF]");
    private static final Pattern TITLE_NOISE =
            Pattern.compile("[\\p{P}\\p{S}\\s]", Pattern.UNICODE_CHARACTER_CLASS);
    private static final int MIN_SIMILAR_TITLE_LENGTH = 4;

    public String contentHash(String courseName, String rawText) {
        return DdlIdempotencyKey.sha256(normalize(courseName) + ":" + normalize(rawText));
    }

    /** 通知文本规范化：NFKC、去零宽字符、去空白、转小写。 */
    public String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
        normalized = ZERO_WIDTH.matcher(normalized).replaceAll("");
        return WHITESPACE.matcher(normalized).replaceAll("").toLowerCase(Locale.ROOT);
    }

    /** 标题规范化：在 {@link #normalize} 基础上去除标点/符号。 */
    public String normalizeTitle(String value) {
        return TITLE_NOISE.matcher(normalize(value)).replaceAll("");
    }

    public boolean isSimilarTitle(String left, String right) {
        String a = normalizeTitle(left);
        String b = normalizeTitle(right);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        if (a.equals(b)) {
            return true;
        }
        String shorter = a.length() <= b.length() ? a : b;
        String longer = a.length() <= b.length() ? b : a;
        return shorter.length() >= MIN_SIMILAR_TITLE_LENGTH && longer.contains(shorter);
    }
}
