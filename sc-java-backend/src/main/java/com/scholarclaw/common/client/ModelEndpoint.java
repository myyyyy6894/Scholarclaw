package com.scholarclaw.common.client;

/**
 * 一次探测所需的最小模型配置（{@code 04} §4.9.1 的 `chat_config` / `embedding_config`）。
 *
 * <p>`apiKey` 是**明文**，只在当次内存请求体内使用（{@code 03} §7.3：明文只在受 `X-Internal-Token`
 * 保护的内部请求中按任务透传），不得写日志、不得缓存、不得出现在异常消息里。
 */
public record ModelEndpoint(String provider, String baseUrl, String apiKey, String modelName) {
}
