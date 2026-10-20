package com.scholarclaw.modules.userconfig.service;

import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * 已保存 API Key 的**目标绑定**规则（安全边界，{@code NFR-SEC-02}）。
 *
 * <p><b>为什么需要它：</b>已保存的 Key 是用户为某个 `provider + Base URL + 模型` 配置的凭据。
 * 如果用户只改了 Base URL/Provider/模型而没有重新输入 Key，却仍然复用旧 Key，等于把旧凭据
 * 发往一个新的目标地址——这是一次意外的凭据外带。所有“使用已保存 Key”的路径都必须先经过本规则。
 *
 * <p><b>允许的复用边界：</b>
 * <ul>
 *   <li>`use_saved_key=true`：provider、规范化 origin、模型名**全部一致**才允许；</li>
 *   <li>`reuse_chat_api_key=true`：产品明确允许把对话 Key 复用于 Embedding 模型，因此只要求
 *       provider 与规范化 origin 一致；模型名可以不同（这是唯一被产品明确允许的例外）；</li>
 *   <li>本次请求**新输入**的 Key 不受此规则约束（它本来就是用户为新目标显式提供的）。</li>
 * </ul>
 *
 * <p>判断集中在 `userconfig` 服务层，Controller 不再散落字符串比较。
 */
@Component
public class ModelKeyBindingRules {

    private static final String BINDING_VIOLATION_MESSAGE =
            "已保存的 API Key 只绑定于原模型配置；修改了供应商、Base URL 或模型名称时，请重新输入 API Key。";

    /** `use_saved_key=true`：provider + origin + 模型名必须全部一致。 */
    public void requireSameTarget(String savedProvider, String savedBaseUrl, String savedModel,
                                  String submittedProvider, String submittedBaseUrl, String submittedModel) {
        if (!isSameTarget(savedProvider, savedBaseUrl, savedModel,
                submittedProvider, submittedBaseUrl, submittedModel)) {
            throw violation();
        }
    }

    /**
     * `reuse_chat_api_key=true`：对话 Key 复用于 Embedding 时，只要求 provider 与 origin 一致；
     * 模型名允许不同（产品明确允许的复用场景）。
     */
    public void requireSameProviderAndOrigin(String savedProvider, String savedBaseUrl,
                                             String submittedProvider, String submittedBaseUrl) {
        if (!isSameProviderAndOrigin(savedProvider, savedBaseUrl, submittedProvider, submittedBaseUrl)) {
            throw violation();
        }
    }

    boolean isSameTarget(String savedProvider, String savedBaseUrl, String savedModel,
                         String submittedProvider, String submittedBaseUrl, String submittedModel) {
        return isSameProviderAndOrigin(savedProvider, savedBaseUrl, submittedProvider, submittedBaseUrl)
                && sameText(savedModel, submittedModel);
    }

    boolean isSameProviderAndOrigin(String savedProvider, String savedBaseUrl,
                                    String submittedProvider, String submittedBaseUrl) {
        if (!sameText(savedProvider, submittedProvider)) {
            return false;
        }
        String savedOrigin = ModelConfigRules.originOf(savedBaseUrl);
        String submittedOrigin = ModelConfigRules.originOf(submittedBaseUrl);
        // origin 解析失败（空串）时不能视为“相同”，否则等于放行到未知目标
        return !savedOrigin.isEmpty() && savedOrigin.equals(submittedOrigin);
    }

    private static boolean sameText(String left, String right) {
        return left != null && right != null
                && left.trim().toUpperCase(Locale.ROOT).equals(right.trim().toUpperCase(Locale.ROOT));
    }

    private static BusinessException violation() {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, BINDING_VIOLATION_MESSAGE);
    }
}
