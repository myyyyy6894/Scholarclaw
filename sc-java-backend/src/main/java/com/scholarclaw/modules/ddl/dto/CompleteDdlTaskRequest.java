package com.scholarclaw.modules.ddl.dto;

import jakarta.validation.constraints.NotNull;

/** 标记完成 / 取消完成勾选请求（{@code 04} `API-DDL-006`）。 */
public record CompleteDdlTaskRequest(
        @NotNull(message = "completed 不能为空")
        Boolean completed) {
}
