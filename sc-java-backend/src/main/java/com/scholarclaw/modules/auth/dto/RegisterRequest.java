package com.scholarclaw.modules.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 注册请求（{@code 04} `API-AUTH-001`）。
 *
 * <p>校验规则逐字来自契约：用户名 `^[a-zA-Z0-9_-]{3,64}$`；密码 `8~64` 位且**必须同时包含字母与数字**；
 * 时区可选，缺省 `Asia/Shanghai`。校验失败统一由 `GlobalExceptionHandler` 映射为
 * `400 VALIDATION_ERROR`（{@code 04} §2.3.2 第 1 类）。
 */
public record RegisterRequest(
        @NotBlank(message = "用户名不能为空")
        @Pattern(regexp = "^[a-zA-Z0-9_-]{3,64}$",
                message = "用户名必须为 3~64 位字母、数字、下划线或连字符")
        String username,

        @NotBlank(message = "密码不能为空")
        @Size(min = 8, max = 64, message = "密码长度必须为 8~64 位")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,64}$",
                message = "密码必须同时包含字母与数字")
        String password,

        @Size(max = 64, message = "timezone 长度不能超过 64")
        String timezone) {
}
