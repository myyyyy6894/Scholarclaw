package com.scholarclaw.modules.userconfig.service;

import com.scholarclaw.common.security.AesGcmCryptoService;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 平台默认向量模型（部署方自带 Embedding 服务）。
 *
 * <p>自建部署常见形态：部署方自己跑一个 Embedding 服务，使用者只提供对话模型 Key。
 * 由于建课、上传解析、RAG 问答三处都要求用户配置里存在完整的 Embedding 三列 + 密文
 * （{@link ModelConfigRules#isEmbeddingConfigValid}），这里在**注册时**把平台默认值写进
 * 该用户的 {@code t_user_config}：下游校验一行不改，用户也可以自行覆盖。
 *
 * <p>三条边界：
 * <ul>
 *   <li>只有五项全部配置（免密本地 Ollama 可省 API Key）才启用；<b>部分配置即启动失败</b>，
 *       避免运维以为已开启、实际每个新用户仍然没有向量模型；</li>
 *   <li>密钥与用户自填的 Key 走同一条加密路径（{@link AesGcmCryptoService}），
 *       不额外引入明文存储；</li>
 *   <li>不改写任何已有用户；只影响配置生效后新注册的账号。</li>
 * </ul>
 */
@Component
public class PlatformEmbeddingDefaults {

    private static final Logger log = LoggerFactory.getLogger(PlatformEmbeddingDefaults.class);

    /** 与 {@link ModelConfigRules} 的 Embedding Provider 集合保持一致（DeepSeek 不提供 Embedding）。 */
    private static final Set<String> EMBEDDING_PROVIDERS = Set.of("OPENAI_COMPATIBLE", "ZHIPU", "OLLAMA");

    private final AesGcmCryptoService cryptoService;
    private final ModelConfigRules modelConfigRules;
    private final String provider;
    private final String baseUrl;
    private final String model;
    private final Integer dim;
    private final String apiKey;

    /** 启用后的写入值；{@code null} 表示未配置（保持"用户自行配置"的原有行为）。 */
    private Seed seed;

    public PlatformEmbeddingDefaults(AesGcmCryptoService cryptoService,
                                     ModelConfigRules modelConfigRules,
                                     @Value("${sc.platform.embedding.provider:}") String provider,
                                     @Value("${sc.platform.embedding.base-url:}") String baseUrl,
                                     @Value("${sc.platform.embedding.model:}") String model,
                                     @Value("${sc.platform.embedding.dim:0}") Integer dim,
                                     @Value("${sc.platform.embedding.api-key:}") String apiKey) {
        this.cryptoService = cryptoService;
        this.modelConfigRules = modelConfigRules;
        this.provider = trim(provider);
        this.baseUrl = trim(baseUrl);
        this.model = trim(model);
        this.dim = dim;
        this.apiKey = trim(apiKey);
    }

    @PostConstruct
    void initialize() {
        boolean anyConfigured = !provider.isEmpty() || !baseUrl.isEmpty() || !model.isEmpty()
                || !apiKey.isEmpty() || (dim != null && dim > 0);
        if (!anyConfigured) {
            seed = null;
            log.info("平台默认向量模型未配置：新用户需在【个人设置 -> 模型配置】自行填写 Embedding");
            return;
        }
        requireConfigured("SC_PLATFORM_EMBEDDING_PROVIDER", provider);
        requireConfigured("SC_PLATFORM_EMBEDDING_BASE_URL", baseUrl);
        requireConfigured("SC_PLATFORM_EMBEDDING_MODEL", model);
        if (dim == null || dim <= 0) {
            throw new IllegalStateException("SC_PLATFORM_EMBEDDING_DIM 必须为正整数（向量维度）。");
        }
        String normalizedProvider = provider.toUpperCase(Locale.ROOT);
        if (!EMBEDDING_PROVIDERS.contains(normalizedProvider)) {
            throw new IllegalStateException("SC_PLATFORM_EMBEDDING_PROVIDER 只支持 "
                    + EMBEDDING_PROVIDERS + "，当前为 " + provider + "。");
        }
        validateBaseUrl(baseUrl);
        boolean keyless = modelConfigRules.isWhitelistedLocalOllama(normalizedProvider, baseUrl);
        if (!keyless && apiKey.isEmpty()) {
            throw new IllegalStateException("SC_PLATFORM_EMBEDDING_API_KEY 未设置；"
                    + "只有命中 SC_LOCAL_MODEL_ALLOWED_ORIGINS 的本地 Ollama 才能免密。");
        }
        seed = new Seed(normalizedProvider, baseUrl, model, dim,
                keyless ? null : cryptoService.encrypt(apiKey));
        log.info("平台默认向量模型已启用 provider={} model={} dim={} 免密={}",
                normalizedProvider, model, dim, keyless);
    }

    /** 注册时写入用户配置的默认值；未启用时为空。 */
    public Optional<Seed> seed() {
        return Optional.ofNullable(seed);
    }

    public boolean isEnabled() {
        return seed != null;
    }

    /**
     * 写入 {@code t_user_config} 的 Embedding 列。
     *
     * <p>{@code apiKeyCipher} 为 {@code null} 表示免密本地 Ollama；
     * 明文 Key 不离开本类，也不进入日志。
     */
    public record Seed(String provider, String baseUrl, String model, Integer dim, String apiKeyCipher) {
    }

    private static void requireConfigured(String name, String value) {
        if (value.isEmpty()) {
            throw new IllegalStateException(name + " 未设置；平台默认向量模型要求五项配置齐全"
                    + "（免密本地 Ollama 也需设置 provider/base-url/model/dim）。");
        }
    }

    private static void validateBaseUrl(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme)) || uri.getHost() == null
                    || uri.getHost().isBlank()) {
                throw new IllegalStateException("SC_PLATFORM_EMBEDDING_BASE_URL 必须是 http(s) 地址，当前为 " + value + "。");
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("SC_PLATFORM_EMBEDDING_BASE_URL 不是合法 URL：" + value, e);
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
