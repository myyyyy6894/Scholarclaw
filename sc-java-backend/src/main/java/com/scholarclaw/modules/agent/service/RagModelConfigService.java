package com.scholarclaw.modules.agent.service;

import com.scholarclaw.common.client.ModelEndpoint;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.common.security.AesGcmCryptoService;
import com.scholarclaw.modules.course.entity.CourseNamespaceEntity;
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import com.scholarclaw.modules.userconfig.service.ModelConfigRules;
import com.scholarclaw.modules.userconfig.service.ModelKeyBindingRules;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import org.springframework.stereotype.Service;

/**
 * RAG 问答前的对话/Embedding 配置解析（TASK-604）。
 *
 * <p>规则与 `KnowledgeParseContextService` 保持一致：非免密供应商必须有 Key，
 * 白名单本地 Ollama 才可以无 Key；Embedding 必须与已保存 Key 的目标绑定一致。
 * 任何缺失都按 `E-SYS-01` 拒绝，不伪造默认模型或维度。
 */
@Service
public class RagModelConfigService {

    private final UserConfigService userConfigService;
    private final ModelConfigRules modelConfigRules;
    private final ModelKeyBindingRules modelKeyBindingRules;
    private final AesGcmCryptoService cryptoService;

    public RagModelConfigService(UserConfigService userConfigService,
                                 ModelConfigRules modelConfigRules,
                                 ModelKeyBindingRules modelKeyBindingRules,
                                 AesGcmCryptoService cryptoService) {
        this.userConfigService = userConfigService;
        this.modelConfigRules = modelConfigRules;
        this.modelKeyBindingRules = modelKeyBindingRules;
        this.cryptoService = cryptoService;
    }

    public RagModelConfig resolve(Long userId, CourseNamespaceEntity course) {
        UserConfigEntity config = userConfigService.findById(userId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.AUTH_UNAUTHORIZED, "登录状态已失效，请重新登录。"));
        if (UserConfigService.isDeactivating(config)) {
            throw new BusinessException(ErrorCode.ACCOUNT_DEACTIVATED);
        }
        if (!modelConfigRules.isChatConfigValid(config)) {
            throw new BusinessException(ErrorCode.E_SYS_01,
                    "⚠️ 尚未配置完整的对话模型，请先前往【个人设置 -> 模型配置】完成配置。");
        }

        ModelEndpoint chat = new ModelEndpoint(config.getChatProvider(), config.getChatBaseUrl(),
                resolveChatKey(config), config.getChatModel());
        ModelEndpoint embedding = resolveEmbedding(config, course);
        return new RagModelConfig(chat, embedding, course.getEmbeddingDim());
    }

    private ModelEndpoint resolveEmbedding(UserConfigEntity config,
                                           CourseNamespaceEntity course) {
        if (course.getEmbeddingProvider() == null || course.getEmbeddingModel() == null
                || course.getEmbeddingDim() == null || course.getEmbeddingDim() <= 0) {
            throw new BusinessException(ErrorCode.E_SYS_01,
                    "⚠️ 当前课程缺少完整的向量模型配置，请先前往【个人设置 -> 模型配置】完成配置。");
        }
        if (!modelConfigRules.isProviderAllowedForProbe("EMBEDDING", course.getEmbeddingProvider())) {
            throw new BusinessException(ErrorCode.E_SYS_01);
        }
        String baseUrl = config.getEmbeddingBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new BusinessException(ErrorCode.E_SYS_01);
        }
        String apiKey;
        if (modelConfigRules.isWhitelistedLocalOllama(course.getEmbeddingProvider(), baseUrl)) {
            apiKey = null;
        } else {
            if (config.getEmbeddingApiKeyCipher() == null) {
                throw new BusinessException(ErrorCode.E_SYS_01);
            }
            try {
                modelKeyBindingRules.requireSameTarget(
                        config.getDefaultEmbeddingProvider(), baseUrl,
                        config.getDefaultEmbeddingModel(), course.getEmbeddingProvider(),
                        baseUrl, course.getEmbeddingModel());
                apiKey = cryptoService.decrypt(config.getEmbeddingApiKeyCipher());
            } catch (RuntimeException e) {
                throw new BusinessException(ErrorCode.E_SYS_01,
                        "Embedding 配置与已保存的 API Key 不匹配，请重新保存模型配置后再试。");
            }
        }
        return new ModelEndpoint(course.getEmbeddingProvider(), baseUrl, apiKey,
                course.getEmbeddingModel());
    }

    private String resolveChatKey(UserConfigEntity config) {
        if (modelConfigRules.isWhitelistedLocalOllama(
                config.getChatProvider(), config.getChatBaseUrl())) {
            return null;
        }
        if (config.getChatApiKeyCipher() == null) {
            throw new BusinessException(ErrorCode.E_SYS_01);
        }
        try {
            return cryptoService.decrypt(config.getChatApiKeyCipher());
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.E_SYS_01,
                    "对话模型 API Key 无法解密，请重新保存模型配置。");
        }
    }

    /** 解析后的 RAG 模型配置；`apiKey` 仅存于当次内存请求体，不落库、不打印。 */
    public record RagModelConfig(ModelEndpoint chat, ModelEndpoint embedding, int embeddingDim) {
    }
}
