package com.scholarclaw.modules.schedule.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.client.ModelEndpoint;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.common.security.AesGcmCryptoService;
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import com.scholarclaw.modules.userconfig.service.ModelConfigRules;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/** Bind each image transmission to the exact BYOK configuration the owner confirmed. */
@Component
public class ScheduleImageModelResolver {
    private final UserConfigService users;
    private final ModelConfigRules rules;
    private final AesGcmCryptoService crypto;
    private final ObjectMapper mapper;

    public ScheduleImageModelResolver(UserConfigService users, ModelConfigRules rules,
                                      AesGcmCryptoService crypto, ObjectMapper mapper) {
        this.users = users; this.rules = rules; this.crypto = crypto; this.mapper = mapper;
    }

    public ModelSelection selection(long userId) { return selection(requireUser(userId)); }

    /** Can also be called with a user row already locked by the caller's transaction. */
    public ModelSelection selection(UserConfigEntity config) {
        requireUsable(config);
        return new ModelSelection(config.getChatProvider(), origin(config.getChatBaseUrl()),
                config.getChatModel(), fingerprint(config));
    }

    /** Resolve once per explicitly approved call; never persist or log the plaintext key. */
    public ModelEndpoint endpoint(long userId, String confirmedFingerprint) {
        return endpoint(requireUser(userId), confirmedFingerprint);
    }

    public ModelEndpoint endpoint(UserConfigEntity config, String confirmedFingerprint) {
        verifyConsent(config, confirmedFingerprint);
        String apiKey = null;
        if (!rules.isWhitelistedLocalOllama(config.getChatProvider(), config.getChatBaseUrl())) {
            try { apiKey = crypto.decrypt(config.getChatApiKeyCipher()); }
            catch (RuntimeException e) {
                throw new BusinessException(ErrorCode.E_SYS_01, "对话模型密钥无法解密，请重新保存模型配置。");
            }
        }
        return new ModelEndpoint(config.getChatProvider(), config.getChatBaseUrl(), apiKey, config.getChatModel());
    }

    /** Check under the user row lock when accepting an upload or retry. */
    public ModelSelection verifyConsent(UserConfigEntity config, String confirmedFingerprint) {
        ModelSelection selected = selection(config);
        if (confirmedFingerprint == null || !confirmedFingerprint.matches("[0-9a-f]{64}")
                || !MessageDigest.isEqual(selected.confirmationToken().getBytes(StandardCharsets.US_ASCII),
                        confirmedFingerprint.getBytes(StandardCharsets.US_ASCII))) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "模型配置已变化，请重新确认图片发送对象。");
        }
        return selected;
    }

    private UserConfigEntity requireUser(long userId) {
        return users.findById(userId).orElseThrow(() -> new BusinessException(ErrorCode.AUTH_UNAUTHORIZED));
    }
    private void requireUsable(UserConfigEntity config) {
        if (config == null || !"ACTIVE".equals(config.getAccountStatus())) {
            throw new BusinessException(ErrorCode.ACCOUNT_DEACTIVATED);
        }
        if (!rules.isChatConfigValid(config)) {
            throw new BusinessException(ErrorCode.E_SYS_01, "请先在个人设置中配置支持图片的对话模型。");
        }
    }
    private String fingerprint(UserConfigEntity config) {
        try {
            byte[] canonical = mapper.writeValueAsBytes(new ConfigSnapshot(config.getChatProvider(),
                    config.getChatBaseUrl(), config.getChatModel(), config.getChatApiKeyCipher()));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }
    private static String origin(String rawUrl) {
        try {
            URI uri = new URI(rawUrl);
            if (uri.getHost() == null || uri.getUserInfo() != null
                    || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
                throw new URISyntaxException("", "invalid origin");
            }
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), null, null, null).toASCIIString();
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.E_SYS_02, "模型地址无效，请重新检查配置。");
        }
    }

    private record ConfigSnapshot(String provider, String baseUrl, String model, String cipher) { }
    public record ModelSelection(String provider, String origin, String modelName, String confirmationToken) { }
}
