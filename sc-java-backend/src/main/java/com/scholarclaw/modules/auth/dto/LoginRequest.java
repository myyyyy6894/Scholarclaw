package com.scholarclaw.modules.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 登录请求（{@code 04} `API-AUTH-002`）。
 *
 * <p>刻意不限制密码复杂度——登录时长度合法性由用户自己负责，**不泄露**"密码格式不符"这类线索；
 * 只做长度上界保护（防超长输入）。
 */
public record LoginRequest(
        @NotBlank(message = "用户名不能为空")
        @Pattern(regexp = "^[a-zA-Z0-9_-]{3,64}$", message = "用户名格式非法")
        String username,

        @NotBlank(message = "密码不能为空")
        @Size(max = 64, message = "密码长度非法")
        String password) {
}
