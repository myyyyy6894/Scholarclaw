package com.scholarclaw.modules.ddl.service;

import com.scholarclaw.common.client.ModelEndpoint;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.common.security.AesGcmCryptoService;
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import com.scholarclaw.modules.userconfig.service.ModelConfigRules;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

/**
 * DDL 提取所需的用户时区与解密后的 Chat 模型配置（TASK-704）。
 *
 * <p>规则与 RAG/文档解析一致：非免密供应商必须有 Key；命中管理员白名单的本地 Ollama
 * 才可以无 Key。明文 Key 只在当次内存请求体中使用，不落库、不打印。
 */
@Component
public class DdlUserConfigResolver {

    private final UserConfigService userConfigService;
    private final ModelConfigRules modelConfigRules;
    private final AesGcmCryptoService cryptoService;
    private final DdlTimeRuleValidator timeRuleValidator;

    public DdlUserConfigResolver(UserConfigService userConfigService,
                                 ModelConfigRules modelConfigRules,
                                 AesGcmCryptoService cryptoService,
                                 DdlTimeRuleValidator timeRuleValidator) {
        this.userConfigService = userConfigService;
        this.modelConfigRules = modelConfigRules;
        this.cryptoService = cryptoService;
        this.timeRuleValidator = timeRuleValidator;
    }

    public ResolvedUser resolve(Long userId) {
        UserConfigEntity config = userConfigService.findById(userId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.AUTH_UNAUTHORIZED, "登录状态已失效，请重新登录。"));
        if (UserConfigService.isDeactivating(config)) {
            throw new BusinessException(ErrorCode.ACCOUNT_DEACTIVATED);
        }
        String timezoneName = timeRuleValidator.normalizeUserTimezone(config.getTimezone());
        return new ResolvedUser(config, timezoneName, timeRuleValidator.resolveUserZone(timezoneName));
    }

    public ModelEndpoint resolveChatEndpoint(UserConfigEntity config) {
        if (!modelConfigRules.isChatConfigValid(config)) {
            throw new BusinessException(ErrorCode.E_SYS_01,
                    "⚠️ 尚未配置完整的对话模型，请先前往【个人设置 -> 模型配置】完成配置。");
        }
        String apiKey = null;
        if (!modelConfigRules.isWhitelistedLocalOllama(
                config.getChatProvider(), config.getChatBaseUrl())) {
            try {
                apiKey = cryptoService.decrypt(config.getChatApiKeyCipher());
            } catch (RuntimeException e) {
                throw new BusinessException(ErrorCode.E_SYS_01,
                        "对话模型 API Key 无法解密，请重新保存模型配置。");
            }
        }
        return new ModelEndpoint(config.getChatProvider(), config.getChatBaseUrl(),
                apiKey, config.getChatModel());
    }

    public record ResolvedUser(UserConfigEntity config, String timezoneName, ZoneId zoneId) {
    }
}
