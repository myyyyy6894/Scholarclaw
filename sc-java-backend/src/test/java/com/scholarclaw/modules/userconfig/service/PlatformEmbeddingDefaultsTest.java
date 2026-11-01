package com.scholarclaw.modules.userconfig.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scholarclaw.common.security.AesGcmCryptoService;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * 平台默认向量模型配置校验（TASK-MVP 上线：部署方自带 Embedding）。
 *
 * <p>重点是"部分配置必须启动失败"：否则运维以为已开启，实际每个新用户仍然没有向量模型，
 * 直到用户建课时才暴露成 `E-SYS-01`。
 */
class PlatformEmbeddingDefaultsTest {

    private static AesGcmCryptoService crypto() {
        return new AesGcmCryptoService(Base64.getEncoder().encodeToString(new byte[32]));
    }

    private static PlatformEmbeddingDefaults build(boolean allowLocal, String origins,
                                                   String provider, String baseUrl, String model,
                                                   Integer dim, String apiKey) {
        return new PlatformEmbeddingDefaults(crypto(), new ModelConfigRules(allowLocal, origins),
                provider, baseUrl, model, dim, apiKey);
    }

    @Test
    void blankConfigDisablesSeeding() {
        PlatformEmbeddingDefaults defaults = build(false, "", "", "", "", 0, "");

        defaults.initialize();

        assertThat(defaults.isEnabled()).isFalse();
        assertThat(defaults.seed()).isEmpty();
    }

    @Test
    void completeConfigProducesEncryptedSeed() {
        PlatformEmbeddingDefaults defaults = build(false, "", "openai_compatible",
                "https://embed.example.com/v1", "text-embedding-3-small", 1536, "sk-platform");

        defaults.initialize();

        assertThat(defaults.isEnabled()).isTrue();
        PlatformEmbeddingDefaults.Seed seed = defaults.seed().orElseThrow();
        assertThat(seed.provider()).isEqualTo("OPENAI_COMPATIBLE");
        assertThat(seed.baseUrl()).isEqualTo("https://embed.example.com/v1");
        assertThat(seed.model()).isEqualTo("text-embedding-3-small");
        assertThat(seed.dim()).isEqualTo(1536);
        // 明文绝不落库：写入的必须是密文，且能被同一主密钥解回原值
        assertThat(seed.apiKeyCipher()).startsWith(AesGcmCryptoService.VERSION_PREFIX);
        assertThat(crypto().decrypt(seed.apiKeyCipher())).isEqualTo("sk-platform");
    }

    @Test
    void apiKeyIsOptionalForWhitelistedLocalOllama() {
        PlatformEmbeddingDefaults defaults = build(true, "http://127.0.0.1:11434", "OLLAMA",
                "http://127.0.0.1:11434/v1", "bge-m3", 1024, "");

        defaults.initialize();

        PlatformEmbeddingDefaults.Seed seed = defaults.seed().orElseThrow();
        assertThat(seed.apiKeyCipher()).isNull();
    }

    @Test
    void partialConfigFailsFast() {
        // 配了地址和模型，但漏了 provider/dim：必须启动失败而不是静默半开
        PlatformEmbeddingDefaults defaults = build(false, "", "", "https://embed.example.com/v1",
                "text-embedding-3-small", 0, "sk-platform");

        assertThatThrownBy(defaults::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SC_PLATFORM_EMBEDDING_PROVIDER");
    }

    @Test
    void missingApiKeyFailsForNonLocalProvider() {
        PlatformEmbeddingDefaults defaults = build(false, "", "OPENAI_COMPATIBLE",
                "https://embed.example.com/v1", "text-embedding-3-small", 1536, "");

        assertThatThrownBy(defaults::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SC_PLATFORM_EMBEDDING_API_KEY");
    }

    @Test
    void unsupportedProviderFailsFast() {
        PlatformEmbeddingDefaults defaults = build(false, "", "DEEPSEEK",
                "https://embed.example.com/v1", "text-embedding-3-small", 1536, "sk-platform");

        assertThatThrownBy(defaults::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SC_PLATFORM_EMBEDDING_PROVIDER");
    }

    @Test
    void nonHttpBaseUrlFailsFast() {
        PlatformEmbeddingDefaults defaults = build(false, "", "OPENAI_COMPATIBLE",
                "ftp://embed.example.com", "text-embedding-3-small", 1536, "sk-platform");

        assertThatThrownBy(defaults::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SC_PLATFORM_EMBEDDING_BASE_URL");
    }
}
