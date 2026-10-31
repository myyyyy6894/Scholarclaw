package com.scholarclaw.common.event;

/**
 * 用户注销第二阶段完成事件（{@code 04} `API-USER-006`）。
 *
 * <p>各模块在 `AFTER_COMMIT` 完成自身物理清理后发布，由 `userconfig` 模块汇总。
 * 只有全部模块都报告完成后，才会物理删除 {@code t_user_config} 行——该行同时是
 * `DeletionGcScheduler` 发现“注销未完成”的唯一标记，过早删除会让残留数据失去补偿入口。
 *
 * @param userId 注销用户 ID
 * @param module 已完成清理的模块
 */
public record UserCleanupCompletedEvent(Long userId, Module module) {

    /** 参与用户注销清理的模块；新增模块时必须同步 {@code UserCleanupRegistry} 的期望集合。 */
    public enum Module {
        COURSE,
        KNOWLEDGE,
        DDL,
        AGENT,
        NOTIFICATION
    }
}
