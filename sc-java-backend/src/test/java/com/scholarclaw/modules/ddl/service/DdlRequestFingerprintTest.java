package com.scholarclaw.modules.ddl.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TASK-704 请求级幂等指纹：同一请求 ID 不得承载不同业务请求。 */
class DdlRequestFingerprintTest {

    @Test
    @DisplayName("DRAFT_CREATE_NEW 指纹包含目标草案身份与影响结果的字段")
    void draftFingerprintIncludesTargetIdentity() {
        DdlTaskDraft base = draft(103L, "2026-10-15T11:40");

        assertThat(DdlRequestFingerprint.forDraftCreateNew(base))
                .isEqualTo(DdlRequestFingerprint.forDraftCreateNew(draft(
                        103L, "2026-10-15T11:40")))
                .isNotEqualTo(DdlRequestFingerprint.forDraftCreateNew(draft(
                        104L, "2026-10-15T11:40")))
                .isNotEqualTo(DdlRequestFingerprint.forDraftCreateNew(draft(
                        103L, "2026-10-16T12:00")))
                .isNotEqualTo(DdlRequestFingerprint.forDraftCreateNew(new DdlTaskDraft(
                        103L, "2026-10-15T11:40", 2001L, "操作系统原理", "另一个标题",
                        "EXACT_DATETIME", "2099-12-31", "22:00:00", "2099-12-31T22:00",
                        false, null, "ACTIVE", null, "zip", "学习通", "原文", "12月31日",
                        "Asia/Shanghai", new BigDecimal("0.960"), false, "hash",
                        "2026-10-16T11:40:00")));
    }

    @Test
    @DisplayName("EXTRACT 指纹区分原文、显式课程与 force_create")
    void extractFingerprintChangesWithContent() {
        String base = DdlRequestFingerprint.forExtract("原文 A", null, false);

        assertThat(base).isEqualTo(DdlRequestFingerprint.forExtract("原文 A", null, false));
        assertThat(base).isNotEqualTo(DdlRequestFingerprint.forExtract("原文 B", null, false));
        assertThat(base).isNotEqualTo(DdlRequestFingerprint.forExtract("原文 A", "2001", false));
        assertThat(base).isNotEqualTo(DdlRequestFingerprint.forExtract("原文 A", null, true));
    }

    private static DdlTaskDraft draft(Long targetTaskId, String targetUpdatedAt) {
        return new DdlTaskDraft(
                targetTaskId, targetUpdatedAt, 2001L, "操作系统原理", "提交实验二报告",
                "EXACT_DATETIME", "2099-12-31", "22:00:00", "2099-12-31T22:00", false,
                null, "ACTIVE", null, "zip", "学习通", "原文", "12月31日",
                "Asia/Shanghai", new BigDecimal("0.960"), false, "hash",
                "2026-10-16T11:40:00");
    }
}
