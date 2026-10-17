/**
 * 模块 7：通知与限流推送（第 3 层）。
 *
 * <p><b>职责：</b>{@code t_notification_log} 表的唯一读写拥有者；维护 {@code OutboundStatus}
 * 四态流转；执行 Redis 令牌桶限流、随机抖动与免打扰判断，并通过
 * {@code OneBotApiClient} 推送 IM 消息。
 *
 * <p><b>允许依赖：</b>{@code userconfig}、{@code common}（{@code OneBotApiClient}）。
 *
 * <p><b>禁止：</b>不得反向依赖顶层 {@code channel} 模块；不负责计算 DDL 何时到期；
 * <b>不得因 IM 发送失败而丢失站内通知保底记录</b>——失败一律落
 * {@code RATE_LIMITED_DELAYED} 或 {@code FALLBACK_WEB_ONLY}，站内信必须始终可查。
 *
 * <p><b>唯一写入权：</b>MySQL {@code t_notification_log}；Redis {@code sc:ratelimit:im_push:*}。
 *
 * <p>落地任务：TASK-802（站内通知中心）、TASK-804（限流与降级）。
 */
package com.scholarclaw.modules.notification;
