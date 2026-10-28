package com.scholarclaw.modules.auth.security;

import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.common.filter.TraceContext;
import com.scholarclaw.common.security.ScUserPrincipal;
import com.scholarclaw.common.security.SecurityErrorResponder;
import com.scholarclaw.modules.auth.jwt.JwtService;
import com.scholarclaw.modules.auth.service.TokenRevocationService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * JWT 认证过滤器（{@code 04} §2.7.1 / §2.7.2）。
 *
 * <p><b>它做什么：</b>解析 `Authorization: Bearer <JWT>` → 验签 → 查 Redis 吊销/注销键 →
 * 把 {@link ScUserPrincipal} 放进 `SecurityContext`，并调用
 * {@link TraceContext#bindUserId} 让后续日志带上真实 `user_id`（TASK-105 预留的钩子在这里闭合）。
 *
 * <p><b>它刻意不做什么：</b>不读取请求体/请求头里的任何 `user_id`——身份只能来自验签通过的 token
 * （{@code 04} §2.7.2 铁律）；也不在本类里判权限，授权交给 `SecurityConfig` 的规则。
 *
 * <p><b>失败关闭：</b>Redis 不可用时**不放行**（黑名单查不了就无法证明 token 未被吊销），
 * 返回 `500 INTERNAL_ERROR` 而不是 401——否则客户端会误以为"登录过期"并清掉本地会话。
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final TokenRevocationService tokenRevocationService;
    private final SecurityErrorResponder responder;

    public JwtAuthenticationFilter(JwtService jwtService, TokenRevocationService tokenRevocationService,
                                   SecurityErrorResponder responder) {
        this.jwtService = jwtService;
        this.tokenRevocationService = tokenRevocationService;
        this.responder = responder;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // OneBot Webhook 使用 Authorization: Bearer <SC_ONEBOT_ACCESS_TOKEN>，不是 JWT；
        // 若在这里尝试验签会把合法 Webhook 直接判成 401。渠道令牌由 channel 模块校验。
        return "/webhooks/onebot/v11".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader(HEADER);
        if (header == null || !header.startsWith(PREFIX)) {
            // 无 token：交给授权规则决定（匿名放行或 401），本过滤器不做拦截
            filterChain.doFilter(request, response);
            return;
        }

        Optional<ScUserPrincipal> verified = jwtService.verify(header.substring(PREFIX.length()).trim());
        if (verified.isEmpty()) {
            responder.respond(response, ErrorCode.AUTH_UNAUTHORIZED, "登录状态已失效，请重新登录。");
            return;
        }

        ScUserPrincipal principal = verified.get();
        TokenRevocationService.TokenStatus status;
        try {
            status = tokenRevocationService.check(principal.userId(), principal.tokenId());
        } catch (RuntimeException e) {
            log.error("吊销状态查询失败，按失败关闭拒绝请求 error={}", e.getClass().getSimpleName());
            responder.respond(response, ErrorCode.INTERNAL_ERROR, "服务暂时不可用，请稍后重试。");
            return;
        }
        if (status.revoked()) {
            responder.respond(response, ErrorCode.AUTH_TOKEN_REVOKED, "登录已失效，请重新登录。");
            return;
        }
        if (status.deactivated()) {
            responder.respond(response, ErrorCode.ACCOUNT_DEACTIVATED, "该账号已注销。");
            return;
        }

        String role = principal.role() == null ? "STUDENT" : principal.role();
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        TraceContext.bindUserId(principal.userId());
        filterChain.doFilter(request, response);
    }
}
