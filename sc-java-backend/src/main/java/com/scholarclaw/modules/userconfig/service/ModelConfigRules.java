package com.scholarclaw.modules.userconfig.service;

import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 模型配置有效性规则（{@code 06} TASK-203、{@code 03} §4.2、{@code 04} §4.2.3）。
 *
 * <p>核心规则（`03` §4.2 专项约束与 `04` §4.2.3 业务规则 1）：**非免密供应商必须有 Key，
 * 命中管理员白名单的本地 Ollama 可以没有 Key**。这条规则在"建课、上传、重试/重建、DDL 提取、
 * RAG 问答"前都会被调用，因此单独收敛在这里，避免各处各写一份、漏掉免密分支。
 *
 * <p>同样重要的一条：**绝不伪造默认值**——provider/model/dim 任一缺失即判定无效，
 * 不填"看似合理"的默认模型或维度（`03` §4.2 明确禁止）。
 */
@Component
public class ModelConfigRules {

    private static final Set<String> CHAT_PROVIDERS =
            Set.of("OPENAI_COMPATIBLE", "DEEPSEEK", "ZHIPU", "OLLAMA");
    private static final Set<String> EMBEDDING_PROVIDERS =
            Set.of("OPENAI_COMPATIBLE", "ZHIPU", "OLLAMA");

    private final boolean allowLocalModelBaseUrl;
    private final Set<String> allowedOrigins;

    public ModelConfigRules(
            @Value("${sc.model.allow-local-base-url:false}") boolean allowLocalModelBaseUrl,
            @Value("${sc.model.allowed-origins:}") String allowedOrigins) {
        this.allowLocalModelBaseUrl = allowLocalModelBaseUrl;
        this.allowedOrigins = Arrays.stream(allowedOrigins == null ? new String[0] : allowedOrigins.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .map(item -> item.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    /** 对话模型配置是否完整有效。 */
    public boolean isChatConfigValid(UserConfigEntity entity) {
        return entity != null
                && CHAT_PROVIDERS.contains(upper(entity.getChatProvider()))
                && hasText(entity.getChatBaseUrl())
                && hasText(entity.getChatModel())
                && (isWhitelistedLocalOllama(entity.getChatProvider(), entity.getChatBaseUrl())
                    || hasText(entity.getChatApiKeyCipher()));
    }

    /** 默认 Embedding 配置是否完整有效（维度必须 > 0，且不得伪造）。 */
    public boolean isEmbeddingConfigValid(UserConfigEntity entity) {
        return entity != null
                && EMBEDDING_PROVIDERS.contains(upper(entity.getDefaultEmbeddingProvider()))
                && hasText(entity.getEmbeddingBaseUrl())
                && hasText(entity.getDefaultEmbeddingModel())
                && entity.getDefaultEmbeddingDim() != null
                && entity.getDefaultEmbeddingDim() > 0
                && (isWhitelistedLocalOllama(entity.getDefaultEmbeddingProvider(), entity.getEmbeddingBaseUrl())
                    || hasText(entity.getEmbeddingApiKeyCipher()));
    }

    /**
     * 是否命中"免密本地 Ollama"白名单（{@code SC_ALLOW_LOCAL_MODEL_BASE_URL} +
     * 精确 origin 命中 {@code SC_LOCAL_MODEL_ALLOWED_ORIGINS}）。
     */
    public boolean isWhitelistedLocalOllama(String provider, String baseUrl) {
        if (!allowLocalModelBaseUrl || !"OLLAMA".equals(upper(provider)) || !hasText(baseUrl)) {
            return false;
        }
        return allowedOrigins.contains(originOf(baseUrl));
    }

    static String originOf(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "";
        }
        try {
            java.net.URI uri = java.net.URI.create(baseUrl.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            return scheme + "://" + host + (port > 0 ? ":" + port : "");
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
