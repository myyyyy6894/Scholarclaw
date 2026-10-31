package com.scholarclaw.modules.userconfig.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 账号注销请求（{@code 04} `API-USER-006` `POST /api/v1/user/account/deactivate`）。
 *
 * <p>必须同时提供当前密码与固定确认口令 `CONFIRM_DEACTIVATE`；缺少任一项都不能注销。
 */
public record DeactivateAccountRequest(
        @NotBlank(message = "password 不能为空")
        @Size(max = 128, message = "password 长度不能超过 128")
        String password,

        @NotBlank(message = "confirm_text 不能为空")
        @Size(max = 32, message = "confirm_text 长度不能超过 32")
        String confirmText) {
}
