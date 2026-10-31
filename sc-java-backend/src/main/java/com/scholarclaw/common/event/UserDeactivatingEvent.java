package com.scholarclaw.common.event;

/**
 * 用户注销第一阶段事件（{@code 04} `API-USER-006`、{@code 03} §6.7）。
 *
 * <p>由 `userconfig` 模块在账号置为 {@code DEACTIVATING} 的本地事务内发布；
 * `course`、`knowledge`、`ddl`、`agent`、`notification` 模块分别监听并冻结/解绑自身数据。
 * 事件只携带用户 ID，不携带任何凭据或个人信息。
 */
public record UserDeactivatingEvent(Long userId) {
}
