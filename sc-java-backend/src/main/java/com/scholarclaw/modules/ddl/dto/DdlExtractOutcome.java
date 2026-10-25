package com.scholarclaw.modules.ddl.dto;

/** `API-DDL-001` 提取处理结果类型（`04` §4.6.1）。 */
public enum DdlExtractOutcome {
    CREATED,
    IDEMPOTENT_HIT,
    SEMANTIC_DUPLICATE_HINT,
    CHANGE_DRAFT_CONFIRM_REQUIRED
}
