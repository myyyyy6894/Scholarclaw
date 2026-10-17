/**
 * 第 1~3 层：业务与入口模块（{@code 02} §4.1 的 Strict DAG）。
 *
 * <p>九个模块及其唯一写入权（{@code 02} §4.3），常量名即包名后缀：
 * <table border="1">
 *   <caption>模块与依赖方向</caption>
 *   <tr><th>层</th><th>模块</th><th>允许依赖的模块</th><th>唯一拥有写入权的存储</th></tr>
 *   <tr><td>1</td><td>channel</td><td>agent, knowledge, userconfig, common</td><td>Redis 入站幂等键</td></tr>
 *   <tr><td>1</td><td>scheduler</td><td>ddl, knowledge, course, userconfig, common</td><td>Redis 任务锁</td></tr>
 *   <tr><td>2</td><td>agent</td><td>ddl, knowledge, course, userconfig, notification, common</td>
 *       <td>t_chat_message</td></tr>
 *   <tr><td>2</td><td>ddl</td><td>course, userconfig, notification, common</td>
 *       <td>t_ddl_task, t_ddl_subtask</td></tr>
 *   <tr><td>2</td><td>knowledge</td><td>course, userconfig, notification, common</td>
 *       <td>t_knowledge_doc, t_knowledge_parent_chunk, rag.ingest.queue/dlq</td></tr>
 *   <tr><td>3</td><td>course</td><td>userconfig, common</td><td>t_course_namespace</td></tr>
 *   <tr><td>3</td><td>auth</td><td>userconfig, common</td><td>Redis JWT 黑名单</td></tr>
 *   <tr><td>3</td><td>userconfig</td><td>common</td><td>t_user_config</td></tr>
 *   <tr><td>3</td><td>notification</td><td>userconfig, common</td><td>t_notification_log</td></tr>
 * </table>
 *
 * <p><b>两条不可违反的规则：</b>
 * <ol>
 *   <li>跨模块只允许注入对方公开的 {@code *Service}，<b>严禁</b>跨模块注入 {@code *Mapper} /
 *       {@code *Repository}（Single-Writer：每张表只有一个模块能写）；</li>
 *   <li>下层模块需要联动上层时，只能发布 {@code common.event} 中的领域事件，不得反向依赖。</li>
 * </ol>
 *
 * <p>以上依赖方向由 {@code com.scholarclaw.architecture.ModuleDependencyTest} 用 ArchUnit
 * 自动断言；新增跨模块调用前必须先确认该表允许，违反时会直接导致构建失败。
 */
package com.scholarclaw.modules;
