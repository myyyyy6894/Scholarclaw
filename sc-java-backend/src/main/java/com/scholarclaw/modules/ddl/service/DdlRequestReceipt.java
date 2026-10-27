package com.scholarclaw.modules.ddl.service;

/**
 * 请求级幂等终态回执（TASK-704 修复，Redis {@code sc:idemp:msg:WEB:{uid}:{client_request_id}}）。
 *
 * <p>`PROCESSED` 不再只是一个字符串：必须能**原样重放**上次处理结果，否则
 * `SEMANTIC_DUPLICATE_HINT` / `CHANGE_DRAFT_CONFIRM_REQUIRED` 的同一请求重试会再次调用模型，
 * 且旧请求 ID 可能被 `force_create=true` 绕过建单。
 *
 * <p>{@code fingerprint} 由规范化原文、显式课程 ID 与 {@code force_create} 计算：
 * 同一 ID 携带不同内容必须返回 {@code IDEMPOTENCY_CONFLICT}，不得重新执行。
 *
 * <p>ID 全部以十进制字符串保存（`docs/03` §8.1：Redis JSON 中的 BIGINT 主键同样必须是字符串）。
 */
public record DdlRequestReceipt(
        int version,
        String fingerprint,
        String outcome,
        String taskId,
        String draftTargetTaskId,
        String expiresAt) {

    public static final int CURRENT_VERSION = 1;

    public static DdlRequestReceipt of(String fingerprint, String outcome, Long taskId,
                                       Long draftTargetTaskId, String expiresAt) {
        return new DdlRequestReceipt(
                CURRENT_VERSION,
                fingerprint,
                outcome,
                taskId == null ? null : taskId.toString(),
                draftTargetTaskId == null ? null : draftTargetTaskId.toString(),
                expiresAt);
    }

    public boolean isCurrentVersion() {
        return version == CURRENT_VERSION;
    }
}
