/**
 * 模块 2：用户配置与密钥（第 3 层）。
 *
 * <p><b>职责：</b>{@code t_user_config} 表的唯一读写拥有者；调用 {@code AesGcmCryptoService}
 * 加解密 API Key；调用 {@code BaseUrlSecurityValidator} 与 {@code PythonAiEngineClient.probeModel()}
 * 完成模型连通性与真实维度探测；发起账号注销并发布 {@code UserDeactivatingEvent}。
 *
 * <p><b>允许依赖：</b>仅 {@code common}（{@code PythonAiEngineClient}、{@code AesGcmCryptoService}、
 * 领域事件）。本模块是最底层业务模块，不得依赖任何其他业务模块。
 *
 * <p><b>禁止与红线：</b>
 * <ul>
 *   <li>账号认证列（组 A）与 BYOK/偏好列（组 B）必须用<b>分开的</b>更新方法，禁止整行覆盖；
 *       否则会出现"改偏好把密钥清空"或"登录刷新把配置覆盖"这类事故；</li>
 *   <li>不得对外返回明文 API Key，只返回掩码；明文与密文均不得进入日志、MQ、Redis；</li>
 *   <li>需要联动 {@code knowledge}/{@code ddl}/{@code agent}/{@code notification} 时只能发事件，
 *       不得反向依赖。</li>
 * </ul>
 *
 * <p><b>唯一写入权：</b>MySQL {@code t_user_config}。
 *
 * <p>落地任务：TASK-201（建表基线）、TASK-203（BYOK 加密）、TASK-204（Base URL 校验）、TASK-901（注销级联）。
 */
package com.scholarclaw.modules.userconfig;
