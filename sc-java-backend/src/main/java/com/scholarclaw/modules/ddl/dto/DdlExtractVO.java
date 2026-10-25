package com.scholarclaw.modules.ddl.dto;

/** `API-DDL-001` 响应 `data`（`04` §4.6.1）。 */
public record DdlExtractVO(
        DdlExtractOutcome extractOutcome,
        DdlTaskVO task,
        TaskChangeDraftVO changeDraft) {
}
