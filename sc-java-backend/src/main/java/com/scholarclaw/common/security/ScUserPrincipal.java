package com.scholarclaw.common.security;

import java.time.Instant;

/**
 * 通过 JWT 验签后的当前用户身份（{@code 04} §2.7.2）。
 *
 * <p>只承载"从签名验证过的 token 里解析出来的事实"：`sub`（user_id）、`username`、`role`、
 * `jti` 与 `exp`。控制器与业务层拿身份**只能**从 {@link SecurityContextUtils} 取，
 * 不允许从请求体/请求头读取 `user_id`（`04` §2.7.2 权限判断铁律）。
 *
 * @param userId    用户 ID（`sub` 解析为 `Long`）
 * @param username  登录用户名
 * @param role      角色（MVP 固定 `STUDENT`）
 * @param tokenId   当前 JWT 的 `jti`，登出时写黑名单用
 * @param expiresAt 当前 JWT 的过期时刻，计算黑名单 TTL 用
 */
public record ScUserPrincipal(Long userId, String username, String role, String tokenId, Instant expiresAt) {
}
