/**
 * 模块 1：认证与安全（第 3 层）。
 *
 * <p><b>职责：</b>注册密码哈希、登录密码 BCrypt 校验、JWT 签发/解析/吊销黑名单维护，
 * 以及 {@code JwtAuthenticationFilter} 提取当前用户 ID。
 *
 * <p><b>允许依赖：</b>{@code userconfig}、{@code common}。
 *
 * <p><b>禁止：</b>本模块不拥有任何数据库 Mapper——{@code t_user_config} 的全部读写都必须委托给
 * {@code UserConfigService}；不得越权写入 BYOK 配置字段；不得从客户端传入的 {@code user_id}
 * 决定当前用户身份。
 *
 * <p><b>唯一写入权：</b>Redis {@code sc:u:{uid}:jwt_blacklist}、{@code sc:u:{uid}:deactivated}。
 *
 * <p>落地任务：TASK-202。
 */
package com.scholarclaw.modules.auth;
