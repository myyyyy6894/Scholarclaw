package com.scholarclaw.modules.userconfig.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BYOK 配置有效性规则（{@code 03} §4.2、{@code 04} §4.2.3）。
 *
 * <p>核心不变量：非免密供应商必须有 Key；只有"开关 + 精确 origin 白名单"同时成立时，
 * 本地 Ollama 才能没有 Key；维度缺失或为 0 一律视为未配置（不得伪造默认值）。
 */
class ModelConfigRulesTest {

    private static final ModelConfigRules RULES = new ModelConfigRules(false, "");
    private static final ModelConfigRules LOCAL_ALLOWED = new ModelConfigRules(
            true, "http://127.0.0.1:11434");

    private static UserConfigEntity chatEntity(String provider, String baseUrl) {
        UserConfigEntity entity = new UserConfigEntity();
        entity.setChatProvider(provider);
        entity.setChatBaseUrl(baseUrl);
        entity.setChatModel("deepseek-chat");
        return entity;
    }

    private static UserConfigEntity embeddingEntity(int dim) {
        UserConfigEntity entity = new UserConfigEntity();
        entity.setDefaultEmbeddingProvider("OPENAI_COMPATIBLE");
        entity.setEmbeddingBaseUrl("https://api.siliconflow.cn/v1");
        entity.setDefaultEmbeddingModel("BAAI/bge-m3");
        entity.setDefaultEmbeddingDim(dim);
        return entity;
    }

    @Test
    @DisplayName("有 Key 的完整对话配置有效；缺 Key 无效")
    void chatConfigRequiresKey() {
        UserConfigEntity withKey = chatEntity("DEEPSEEK", "https://api.deepseek.com/v1");
        withKey.setChatApiKeyCipher("v1:iv:cipher");
        assertThat(RULES.isChatConfigValid(withKey)).isTrue();

        UserConfigEntity withoutKey = chatEntity("DEEPSEEK", "https://api.deepseek.com/v1");
        assertThat(RULES.isChatConfigValid(withoutKey)).isFalse();
    }

    @Test
    @DisplayName("白名单内的本地 Ollama 可以无 Key；未命中白名单或开关关闭则无效")
    void localOllamaWithoutKeyRequiresWhitelist() {
        UserConfigEntity local = chatEntity("OLLAMA", "http://127.0.0.1:11434");
        assertThat(LOCAL_ALLOWED.isChatConfigValid(local)).isTrue();
        assertThat(RULES.isChatConfigValid(local)).isFalse();

        UserConfigEntity otherPort = chatEntity("OLLAMA", "http://127.0.0.1:11435");
        assertThat(LOCAL_ALLOWED.isChatConfigValid(otherPort)).isFalse();
    }

    @Test
    @DisplayName("供应商、模型名或 Base URL 缺失时不得伪造默认值，一律判无效")
    void unknownProviderOrMissingFieldsIsInvalid() {
        UserConfigEntity unknownProvider = chatEntity("OPENAI", "https://api.example.com/v1");
        unknownProvider.setChatApiKeyCipher("v1:iv:cipher");
        assertThat(RULES.isChatConfigValid(unknownProvider)).isFalse();

        UserConfigEntity noModel = chatEntity("DEEPSEEK", "https://api.deepseek.com/v1");
        noModel.setChatModel(" ");
        noModel.setChatApiKeyCipher("v1:iv:cipher");
        assertThat(RULES.isChatConfigValid(noModel)).isFalse();
    }

    @Test
    @DisplayName("Embedding 配置要求维度 > 0 且 Provider 在允许集合内")
    void embeddingConfigRequiresPositiveDimension() {
        UserConfigEntity valid = embeddingEntity(1024);
        valid.setEmbeddingApiKeyCipher("v1:iv:cipher");
        assertThat(RULES.isEmbeddingConfigValid(valid)).isTrue();

        UserConfigEntity zeroDim = embeddingEntity(0);
        zeroDim.setEmbeddingApiKeyCipher("v1:iv:cipher");
        assertThat(RULES.isEmbeddingConfigValid(zeroDim)).isFalse();

        UserConfigEntity nullDim = embeddingEntity(1024);
        nullDim.setDefaultEmbeddingDim(null);
        nullDim.setEmbeddingApiKeyCipher("v1:iv:cipher");
        assertThat(RULES.isEmbeddingConfigValid(nullDim)).isFalse();

        UserConfigEntity chatOnlyProvider = embeddingEntity(1024);
        chatOnlyProvider.setDefaultEmbeddingProvider("DEEPSEEK");
        chatOnlyProvider.setEmbeddingApiKeyCipher("v1:iv:cipher");
        assertThat(RULES.isEmbeddingConfigValid(chatOnlyProvider)).isFalse();
    }

    @Test
    @DisplayName("origin 归一化忽略大小写、默认端口与路径，但保留显式端口")
    void originNormalization() {
        assertThat(ModelConfigRules.originOf("HTTP://127.0.0.1:11434/v1")).isEqualTo("http://127.0.0.1:11434");
        assertThat(ModelConfigRules.originOf("https://api.example.com/v1")).isEqualTo("https://api.example.com");
        assertThat(ModelConfigRules.originOf("  ")).isEmpty();
    }
}
