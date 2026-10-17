/**
 * 模块 9：后台定时调度（第 1 层）。
 *
 * <p><b>职责：</b>三个定时器入口——{@code DdlReminderScheduler}（每 1 分钟）、
 * {@code ZombieIngestScanner}（每 5 分钟）、{@code DeletionGcScheduler}（每 60 分钟）。
 *
 * <p><b>允许依赖：</b>{@code ddl}、{@code knowledge}、{@code course}、{@code userconfig}、{@code common}。
 *
 * <p><b>禁止与约束：</b>
 * <ul>
 *   <li>本模块只作为定时器触发入口，所有状态变更与清理都必须调用上述模块的公开 Service，
 *       不得自行写库；</li>
 *   <li>每个定时任务必须显式声明时区，使用注入的 {@code ZoneId}
 *       （{@code @Scheduled(zone = ...)}），严禁依赖 JVM 默认时区（ENV-01）；</li>
 *   <li>定时任务入口需生成 {@code tr_cron_*} 追踪号（{@code 02} §11），
 *       见 {@code com.scholarclaw.common.filter.TraceContext#generateCronTraceId()}。</li>
 * </ul>
 *
 * <p><b>唯一写入权：</b>Redis {@code sc:lock:job:*}（任务锁，防止多实例重复执行）。
 *
 * <p>落地任务：TASK-801（提醒调度）、TASK-902（孤儿资源扫描与 GC）。
 */
package com.scholarclaw.modules.scheduler;
