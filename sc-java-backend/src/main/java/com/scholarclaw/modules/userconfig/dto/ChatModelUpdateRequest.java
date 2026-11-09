package com.scholarclaw.modules.userconfig.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 只更新对话模型，不要求配置向量模型。 */
public record ChatModelUpdateRequest(
        @NotBlank @Pattern(regexp = "OPENAI_COMPATIBLE|DEEPSEEK|ZHIPU|OLLAMA") String chatProvider,
        @NotBlank @Size(min = 8, max = 512) String chatBaseUrl,
        @Size(max = 512) @Pattern(regexp = "^[\\x21-\\x7E]+$") String chatApiKey,
        @NotBlank @Size(max = 128) String chatModel,
        Boolean confirmProbe) { }
