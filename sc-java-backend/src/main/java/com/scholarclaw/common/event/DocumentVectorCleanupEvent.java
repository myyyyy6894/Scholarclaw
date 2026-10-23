package com.scholarclaw.common.event;

/**
 * 文档衍生向量清理请求（TASK-506 复核修复）。
 *
 * <p>只携带身份与版本，不携带任何凭据。发布方必须在数据库事务内发布；
 * 监听方在 `AFTER_COMMIT` 执行 ChromaDB 网络调用，避免把外部 I/O 放进事务。
 *
 * @param parseVersion 为空表示清理该文档全部版本的向量；否则只清理指定解析版本
 */
public record DocumentVectorCleanupEvent(Long userId, Long courseId, Long docId,
                                         Integer parseVersion) {
}
