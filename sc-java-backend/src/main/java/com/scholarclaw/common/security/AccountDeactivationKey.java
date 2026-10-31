package com.scholarclaw.common.security;

import java.time.Duration;

/**
 * 账号注销阻断键与“近期活跃”负缓存的唯一定义（{@code 03} §8.1 第 6 项）。
 *
 * <p>`auth` 的 JWT 过滤器与 `userconfig` 的注销流程必须使用完全相同的键与 TTL，
 * 因此把格式收敛到 `common`，禁止各自拼接字符串。
 *
 * <p><b>TTL 必须覆盖全部已签发 JWT（P9 复核第 5 项）：</b>固定 7 天只在
 * {@code SC_JWT_TTL_SECONDS <= 7 天} 时成立；TTL 可配置后必须按实际 JWT 有效期动态计算并
 * 加余量，否则阻断键先过期而旧 JWT 仍可用。{@link #ttlFor(long)} 取
 * {@code max(7 天, JWT TTL + 1 天)}，并作为注销流程与过滤器共享的唯一算法。
 */
public final class AccountDeactivationKey {

    /** 默认基线：JWT TTL 为 7 天时阻断键至少 7 天。 */
    public static final Duration TTL = Duration.ofDays(7);

    /** JWT 过期之外的额外余量，避免时钟漂移/迁移窗口。 */
    private static final Duration SAFETY_MARGIN = Duration.ofDays(1);

    private AccountDeactivationKey() {
    }

    public static String of(Long userId) {
        return "sc:u:" + userId + ":deactivated";
    }

    /**
     * 近期活跃负缓存：JWT 过滤器在注销键未命中时先看它；命中则无需每请求查库。
     * 注销流程必须删除该键，确保数据库兜底立即生效。
     */
    public static String activeMarker(Long userId) {
        return "sc:u:" + userId + ":active";
    }

    /**
     * 按实际 JWT TTL 计算阻断键 TTL。
     *
     * @param jwtTtlSeconds 已签发的最大 JWT 有效期（秒）；非正数回退到 7 天基线
     */
    public static Duration ttlFor(long jwtTtlSeconds) {
        long base = TTL.toSeconds();
        if (jwtTtlSeconds <= 0) {
            return TTL;
        }
        long required = jwtTtlSeconds + SAFETY_MARGIN.toSeconds();
        return Duration.ofSeconds(Math.max(base, required));
    }
}
