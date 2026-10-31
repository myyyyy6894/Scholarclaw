package com.scholarclaw.common.security;

import java.time.Duration;

/**
 * 账号注销阻断键的唯一定义（{@code 03} §8.1 第 6 项）。
 *
 * <p>`auth` 的 JWT 过滤器与 `userconfig` 的注销流程必须使用完全相同的键与 TTL，
 * 因此把格式收敛到 `common`，禁止各自拼接字符串。值为固定 `"1"`，TTL = 7 天，
 * 覆盖所有已签发 JWT 的最大有效期。
 */
public final class AccountDeactivationKey {

    public static final Duration TTL = Duration.ofDays(7);

    private AccountDeactivationKey() {
    }

    public static String of(Long userId) {
        return "sc:u:" + userId + ":deactivated";
    }
}
