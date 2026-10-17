/**
 * 进程内领域事件契约层（{@code 02} §4.2 第 3 类通信机制）。
 *
 * <p><b>用途：</b>当底层基础模块的状态变更需要联动多个上层业务模块时，用 Spring
 * {@code ApplicationEventPublisher} 发布事件，由上层模块 {@code @EventListener} 监听，
 * 从而彻底消除反向依赖（{@code 02} §4.1 解耦规则 2）。
 *
 * <p><b>MVP 只有三个事件：</b>
 * <ul>
 *   <li>{@code UserDeactivatingEvent}：{@code userconfig} 发起账号注销（发布方 TASK-202/901，
 *       监听方 {@code knowledge} / {@code ddl} / {@code agent} / {@code notification}）；</li>
 *   <li>{@code CourseEmbeddingChangedEvent}：{@code course} 切换课程 Embedding 模型触发全课重建
 *       （发布方 TASK-401，监听方 {@code knowledge}）；</li>
 *   <li>{@code CourseDeletingEvent}：{@code course} 删除课程空间（发布方 TASK-401，
 *       监听方 {@code knowledge} 清理文档与向量、{@code ddl} 解绑保留任务）。</li>
 * </ul>
 *
 * <p><b>实现落地约定：</b>数据库状态原子切换使用同步 {@code @EventListener}（与发布方共享同一本地事务）；
 * 事务提交后才进行的 MQ 投递、ChromaDB 与物理文件清理使用
 * {@code @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)}（{@code 02} §4.2）。
 *
 * <p>本任务（TASK-101）只建立包边界；事件类与监听器随上述发布/监听任务落地，避免在此凭空推断
 * 事件字段导致后续契约漂移。
 */
package com.scholarclaw.common.event;
