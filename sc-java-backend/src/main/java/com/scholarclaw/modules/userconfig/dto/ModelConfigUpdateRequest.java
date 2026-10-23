package com.scholarclaw.modules.userconfig.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 保存 BYOK 模型配置请求（{@code 04} `API-USER-003` `PUT /api/v1/user/model-config`）。
 *
 * <p>Key 字段可为 `null`：表示"保留已保存的 Key 不变"（前端不回显明文）。
 * `reuse_chat_api_key=true` 时用对话模型的 Key 独立加密后写入 Embedding 密文列。
 */
public record ModelConfigUpdateRequest(
        @NotBlank(message = "chat_provider 不能为空")
        @Pattern(regexp = "OPENAI_COMPATIBLE|DEEPSEEK|ZHIPU|OLLAMA", message = "chat_provider 取值非法")
        String chatProvider,

        @NotBlank(message = "chat_base_url 不能为空")
        @Size(min = 8, max = 512, message = "chat_base_url 长度必须为 8~512")
        String chatBaseUrl,

        @Size(max = 512, message = "chat_api_key 长度不能超过 512")
        @Pattern(regexp = "^[\\x21-\\x7E]+$", message = "chat_api_key 只能包含可见 ASCII 字符")
        String chatApiKey,

        @NotBlank(message = "chat_model 不能为空")
        @Size(min = 1, max = 128, message = "chat_model 长度必须为 1~128")
        String chatModel,

        @NotBlank(message = "default_embedding_provider 不能为空")
        @Pattern(regexp = "OPENAI_COMPATIBLE|ZHIPU|OLLAMA", message = "default_embedding_provider 取值非法")
        String defaultEmbeddingProvider,

        @NotBlank(message = "embedding_base_url 不能为空")
        @Size(min = 8, max = 512, message = "embedding_base_url 长度必须为 8~512")
        String embeddingBaseUrl,

        boolean reuseChatApiKey,

        @Size(max = 512, message = "embedding_api_key 长度不能超过 512")
        @Pattern(regexp = "^[\\x21-\\x7E]+$", message = "embedding_api_key 只能包含可见 ASCII 字符")
        String embeddingApiKey,

        @NotBlank(message = "default_embedding_model 不能为空")
        @Size(min = 1, max = 128, message = "default_embedding_model 长度必须为 1~128")
        String defaultEmbeddingModel,

        /**
         * 已有课程存在且 Embedding 目标变化时，必须显式确认迁移；否则后端拒绝覆盖旧 Key，
         * 避免旧课程因 Key 目标绑定失效而无法继续解析。
         */
        boolean migrateExistingCourses) {
}
