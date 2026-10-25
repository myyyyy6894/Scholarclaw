package com.scholarclaw.modules.ddl.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 变更草案决策请求（`04` `API-DDL-005`）。 */
public record DraftDecisionRequest(
        @NotBlank(message = "decision 不能为空")
        String decision,

        @Size(max = 128, message = "client_request_id 长度不能超过 128")
        String clientRequestId) {
}
