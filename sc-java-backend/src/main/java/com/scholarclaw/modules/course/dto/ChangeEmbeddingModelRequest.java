package com.scholarclaw.modules.course.dto;

import jakarta.validation.constraints.Size;

/**
 * 切换课程 Embedding 模型请求（{@code 04} `API-COURSE-003`）。
 *
 * <p>`useUserDefault` 缺省视为 `true`；为 `false` 时 `embeddingProvider` 与
 * `embeddingModel` 由服务层校验为必填。Provider 仍只接受 Embedding 允许的固定枚举。
 */
public record ChangeEmbeddingModelRequest(
        Boolean useUserDefault,

        @Size(max = 32, message = "embedding_provider 长度不能超过 32")
        String embeddingProvider,

        @Size(max = 128, message = "embedding_model 长度不能超过 128")
        String embeddingModel) {

    public boolean usesUserDefault() {
        return useUserDefault == null || useUserDefault;
    }
}
