package com.scholarclaw.modules.auth.controller;

import com.scholarclaw.common.response.Result;
import com.scholarclaw.common.security.ScUserPrincipal;
import com.scholarclaw.common.security.SecurityContextUtils;
import com.scholarclaw.modules.auth.dto.AuthTokenResponse;
import com.scholarclaw.modules.auth.dto.LoginRequest;
import com.scholarclaw.modules.auth.dto.RegisterRequest;
import com.scholarclaw.modules.auth.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证生命周期接口（{@code 04} §4.1：`API-AUTH-001/002/003`）。
 *
 * <p>注册与登录在 {@code SecurityConfig} 中显式放行（匿名可访问）；登出必须带有效 JWT。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** 注册：成功返回 201 + 直接签发 token（{@code 04} `API-CONF-01` 采用方案 A）。 */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Result<AuthTokenResponse> register(@Valid @RequestBody RegisterRequest request) {
        return Result.ok("注册成功", authService.register(request));
    }

    @PostMapping("/login")
    public Result<AuthTokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return Result.ok("登录成功", authService.login(request));
    }

    /** 登出：身份与 `jti` 来自验签通过的 token，不接受任何请求体参数。 */
    @PostMapping("/logout")
    public Result<Void> logout() {
        ScUserPrincipal principal = SecurityContextUtils.currentPrincipal();
        Long userId = SecurityContextUtils.requireCurrentUserId();
        authService.logout(userId, principal.tokenId(), principal.expiresAt());
        return Result.ok("已安全退出登录", null);
    }
}
