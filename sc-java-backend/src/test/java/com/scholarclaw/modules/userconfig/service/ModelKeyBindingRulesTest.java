package com.scholarclaw.modules.userconfig.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 已保存 Key 的目标绑定（安全边界）：旧 Key 不得被发往新的 provider/origin/model。
 */
class ModelKeyBindingRulesTest {

    private final ModelKeyBindingRules rules = new ModelKeyBindingRules();

    @Test
    @DisplayName("provider + 规范化 origin + 模型完全一致时允许复用")
    void allowsExactTarget() {
        assertThatCode(() -> rules.requireSameTarget(
                "DEEPSEEK", "https://API.deepseek.com/v1/", "deepseek-chat",
                "deepseek", "https://api.deepseek.com/v1", "deepseek-chat"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("provider、origin 或模型任一变化都拒绝")
    void rejectsAnyTargetChange() {
        assertThatThrownBy(() -> rules.requireSameTarget(
                "DEEPSEEK", "https://api.deepseek.com/v1", "deepseek-chat",
                "OPENAI_COMPATIBLE", "https://api.deepseek.com/v1", "deepseek-chat"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> org.assertj.core.api.Assertions
                        .assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));

        assertThatThrownBy(() -> rules.requireSameTarget(
                "DEEPSEEK", "https://api.deepseek.com/v1", "deepseek-chat",
                "DEEPSEEK", "https://evil.example.com/v1", "deepseek-chat"))
                .isInstanceOf(BusinessException.class);

        assertThatThrownBy(() -> rules.requireSameTarget(
                "DEEPSEEK", "https://api.deepseek.com/v1", "deepseek-chat",
                "DEEPSEEK", "https://api.deepseek.com/v1", "deepseek-reasoner"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("reuse_chat_api_key：同 provider + 同 origin 时允许不同模型（产品明确允许的复用）")
    void reuseAllowsDifferentModelButNotDifferentOrigin() {
        assertThatCode(() -> rules.requireSameProviderAndOrigin(
                "OPENAI_COMPATIBLE", "https://api.siliconflow.cn/v1",
                "openai_compatible", "https://api.siliconflow.cn/v1/"))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> rules.requireSameProviderAndOrigin(
                "OPENAI_COMPATIBLE", "https://api.siliconflow.cn/v1",
                "OPENAI_COMPATIBLE", "https://evil.example.com/v1"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("模型名仅大小写变化也必须拒绝复用旧 Key")
    void rejectsCaseOnlyModelChange() {
        assertThatThrownBy(() -> rules.requireSameTarget(
                "DEEPSEEK", "https://api.deepseek.com/v1", "deepseek-chat",
                "DEEPSEEK", "https://api.deepseek.com/v1", "DeepSeek-Chat"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> org.assertj.core.api.Assertions
                        .assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    @DisplayName("模型名 trim 后完全一致仍允许复用")
    void trimsModelNameBeforeComparison() {
        assertThatCode(() -> rules.requireSameTarget(
                "DEEPSEEK", "https://api.deepseek.com/v1", " deepseek-chat ",
                "DEEPSEEK", "https://api.deepseek.com/v1", "deepseek-chat"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("无法解析 origin 时不视为相同（不能放行到未知目标）")
    void rejectsUnparseableOrigin() {
        assertThatThrownBy(() -> rules.requireSameTarget(
                "DEEPSEEK", "not a url", "deepseek-chat",
                "DEEPSEEK", "not a url", "deepseek-chat"))
                .isInstanceOf(BusinessException.class);
    }
}
