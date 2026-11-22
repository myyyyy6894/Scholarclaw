package com.scholarclaw.modules.userconfig.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 个人偏好请求（{@code 04} `API-USER-004` `PUT /api/v1/user/preferences`）。
 *
 * <p>免打扰时刻接受 `HH:mm` 与 `HH:mm:ss` 两种写法（`04` §2.5 请求入参兼容），
 * 解析与合法性校验在 service 里做——用注解写时间格式只会得到一条含糊的"格式错误"。
 */
public record PreferencesRequest(
        @NotBlank(message = "timezone 不能为空")
        @Size(min = 1, max = 64, message = "timezone 长度必须为 1~64")
        String timezone,

        @NotBlank(message = "dnd_start_time 不能为空")
        @Size(max = 8, message = "dnd_start_time 长度必须为 1~8")
        String dndStartTime,

        @NotBlank(message = "dnd_end_time 不能为空")
        @Size(max = 8, message = "dnd_end_time 长度必须为 1~8")
        String dndEndTime) {
}
