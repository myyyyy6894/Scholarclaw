package com.scholarclaw.common.security;

import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 当前用户身份上下文的唯一读取入口（{@code 04} §2.7.2）。
 *
 * <p><b>为什么单独建一个工具类：</b>业务代码若直接 `SecurityContextHolder.getContext()`，
 * 会在无请求上下文（定时任务、单元测试）里悄悄拿到 null 并继续执行，导致"空用户"数据落库。
 * 这里把"要么拿到真实用户、要么抛 401"这条规则收敛到一个地方。
 *
 * <p>注意：本类只读 Spring Security 的上下文，不解析任何请求头——身份的**唯一**来源是
 * `JwtAuthenticationFilter` 验签通过的 JWT（`04` §2.7.2）。
 */
public final class SecurityContextUtils {

    private SecurityContextUtils() {
    }

    /** 当前请求的用户身份；未认证时返回 {@code null}（供"可选登录"场景使用）。 */
    public static ScUserPrincipal currentPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        return authentication.getPrincipal() instanceof ScUserPrincipal principal ? principal : null;
    }

    /** 当前请求的用户 ID；未认证时返回 {@code null}。 */
    public static Long currentUserId() {
        ScUserPrincipal principal = currentPrincipal();
        return principal == null ? null : principal.userId();
    }

    /**
     * 当前请求的用户 ID；未认证时抛 {@link ErrorCode#AUTH_UNAUTHORIZED}（401）。
     *
     * <p>需要用户身份的接口一律用它，不要写 `if (userId == null) return;` 之类的静默分支。
     */
    public static Long requireCurrentUserId() {
        Long userId = currentUserId();
        if (userId == null) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED, "登录状态已失效，请重新登录。");
        }
        return userId;
    }
}
