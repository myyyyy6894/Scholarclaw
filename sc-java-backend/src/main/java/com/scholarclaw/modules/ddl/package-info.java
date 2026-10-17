/**
 * 模块 5：DDL 任务状态机（第 2 层）。
 *
 * <p><b>职责：</b>{@code t_ddl_task}（v1.1 追加 {@code t_ddl_subtask}）的唯一读写拥有者；
 * 四级时间精度校验、三层幂等防重与 Redis 变更草案管理；任务查询、确认激活、编辑、完成、
 * 取消删除与到期提醒扫描；监听 {@code CourseDeletingEvent}（关联任务 {@code course_id} 置
 * {@code NULL} 解绑保留）与 {@code UserDeactivatingEvent}。
 *
 * <p><b>允许依赖：</b>{@code course}、{@code userconfig}、{@code notification}、{@code common}。
 *
 * <p><b>禁止与不变量：</b>
 * <ul>
 *   <li>不得直接调用外部大模型 API（结构化提取走 {@code PythonAiEngineClient}）；</li>
 *   <li>不得由 {@code agent} 等模块直接写本模块的表；</li>
 *   <li>不得静默覆盖已有任务——重复通知必须生成变更草案等待用户决策；</li>
 *   <li>{@code PENDING_TIME} / {@code PENDING_CONFIRM} 绝不触发 T-24h / T-6h / 逾期提醒。</li>
 * </ul>
 *
 * <p><b>唯一写入权：</b>MySQL {@code t_ddl_task}、{@code t_ddl_subtask}；Redis {@code sc:u:{uid}:task_draft:*}。
 *
 * <p>落地任务：TASK-701~705、TASK-801（提醒扫描）。
 */
package com.scholarclaw.modules.ddl;
