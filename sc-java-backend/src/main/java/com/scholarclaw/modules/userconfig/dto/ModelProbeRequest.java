package com.scholarclaw.modules.userconfig.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 模型连通性探测请求（{@code 04} `API-USER-002` `POST /api/v1/user/model-config/probe`）。
 *
 * <p><b>公共接口只接受 `CHAT` / `EMBEDDING`：</b>请求体只有一组 provider/base_url/api_key/model_name，
 * 无法同时表达"对话模型"和"Embedding 模型"两套不同配置。`BOTH` 仅保留在
 * Java → Python 的内部探测接口中，供 `API-USER-003` 保存流程使用。
 *
 * <p>`api_key` 可为空：当 `use_saved_key=true` 时后端解密已保存的 Key 去探测（前端不必回显明文）。
 */
public record ModelProbeRequest(
        @NotBlank(message = "probe_type 不能为空")
        @Pattern(regexp = "CHAT|EMBEDDING", message = "probe_type 必须为 CHAT 或 EMBEDDING")
        String probeType,

        @NotBlank(message = "provider 不能为空")
        @Pattern(regexp = "OPENAI_COMPATIBLE|DEEPSEEK|ZHIPU|OLLAMA", message = "provider 取值非法")
        String provider,

        @NotBlank(message = "base_url 不能为空")
        @Size(min = 8, max = 512, message = "base_url 长度必须为 8~512")
        String baseUrl,

        String apiKey,

        boolean useSavedKey,

        @NotBlank(message = "model_name 不能为空")
        @Size(min = 1, max = 128, message = "model_name 长度必须为 1~128")
        String modelName) {
}
