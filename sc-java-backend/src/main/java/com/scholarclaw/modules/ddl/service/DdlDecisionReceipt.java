package com.scholarclaw.modules.ddl.service;

/**
 * `API-DDL-005` 决策终态回执（Redis {@code sc:idemp:decision:WEB:{uid}:{client_request_id}}）。
 *
 * <p>契约把 `API-DDL-005` 标为请求幂等：同一 `client_request_id` 超时重试必须返回原结果。
 * 草案在首次决策后会被删除，若没有回执，重试只能得到 `404 DRAFT_EXPIRED_OR_NOT_FOUND`，
 * 违反幂等语义。
 */
public record DdlDecisionReceipt(
        int version,
        String fingerprint,
        String decision,
        String targetTaskId,
        String resultTaskId,
        String expiresAt) {

    public static final int CURRENT_VERSION = 1;

    public static DdlDecisionReceipt of(String fingerprint, String decision, Long targetTaskId,
                                        Long resultTaskId, String expiresAt) {
        return new DdlDecisionReceipt(
                CURRENT_VERSION,
                fingerprint,
                decision,
                targetTaskId == null ? null : targetTaskId.toString(),
                resultTaskId == null ? null : resultTaskId.toString(),
                expiresAt);
    }
}
