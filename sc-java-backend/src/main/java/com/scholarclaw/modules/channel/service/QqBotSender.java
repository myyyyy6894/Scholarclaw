package com.scholarclaw.modules.channel.service;

import com.scholarclaw.common.client.QqBotApiClient;
import com.scholarclaw.common.im.OfficialBotSender;
import com.scholarclaw.common.security.AesGcmCryptoService;
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 按用户发送 QQ 官方机器人消息。
 *
 * <p>凭据每次从 {@code t_user_config} 读取并解密，不在内存里长期持有明文 AppSecret
 * （只有连接对象在建连时短暂持有）。发送失败只影响 IM 送达，站内通知不受影响。
 */
@Service
public class QqBotSender implements OfficialBotSender {

    private static final Logger log = LoggerFactory.getLogger(QqBotSender.class);

    private final UserConfigService userConfigService;
    private final AesGcmCryptoService cryptoService;
    private final QqBotApiClient apiClient;

    public QqBotSender(UserConfigService userConfigService,
                       AesGcmCryptoService cryptoService,
                       QqBotApiClient apiClient) {
        this.userConfigService = userConfigService;
        this.cryptoService = cryptoService;
        this.apiClient = apiClient;
    }

    /** 用户是否配置了自己的官方机器人（用于出站通道选择）。 */
    @Override
    public boolean isConfigured(Long userId) {
        return userConfigService.findById(userId)
                .map(config -> "ACTIVE".equals(config.getAccountStatus())
                        && config.getQqbotAppId() != null && config.getQqbotSecretCipher() != null)
                .orElse(false);
    }

    /** 被动回复：带 msg_id 与递增 msg_seq（单聊 60 分钟内、每条最多 4 次）。 */
    public void reply(Long userId, String appId, Long version, String openid, String messageId,
                      int msgSeq, String content) {
        UserConfigEntity config = userConfigService.findById(userId).orElse(null);
        if (!verifiedFor(config, appId, version, openid)) {
            return;
        }
        Credential credential = credential(config);
        if (credential == null) {
            return;
        }
        apiClient.sendC2cText(credential.appId(), credential.secret(), openid, content, messageId, msgSeq);
    }

    /**
     * 主动推送：不带 msg_id，受平台配额约束（1000 条/用户/天等）。
     *
     * @return 是否真正投递；用户还没私聊过机器人（无 openid）或凭据不可用时返回 false
     */
    @Override
    public boolean sendProactive(Long userId, String content) {
        UserConfigEntity config = userConfigService.findById(userId).orElse(null);
        String openid = config == null ? null : config.getQqbotOpenid();
        if (config == null || !verifiedFor(config, config.getQqbotAppId(),
                config.getQqbotBindingVersion(), openid)) {
            log.debug("用户尚未验证 QQ 身份，跳过主动推送 userId={}", userId);
            return false;
        }
        Credential credential = credential(config);
        if (credential == null) {
            return false;
        }
        apiClient.sendC2cText(credential.appId(), credential.secret(), openid, content, null, 0);
        return true;
    }

    private boolean verifiedFor(UserConfigEntity config, String appId, Long version, String openid) {
        return config != null && "ACTIVE".equals(config.getAccountStatus())
                && appId != null && appId.equals(config.getQqbotAppId())
                && version != null && version.equals(config.getQqbotBindingVersion())
                && Integer.valueOf(1).equals(config.getQqbotBindingVerified())
                && openid != null && !openid.isBlank() && openid.equals(config.getQqbotOpenid());
    }

    private Credential credential(UserConfigEntity config) {
        if (config == null || config.getQqbotAppId() == null || config.getQqbotSecretCipher() == null) {
            return null;
        }
        try {
            return new Credential(config.getQqbotAppId(),
                    cryptoService.decrypt(config.getQqbotSecretCipher()));
        } catch (RuntimeException e) {
            log.warn("机器人凭据解密失败 userId={} error={}", config.getUserId(), e.getClass().getSimpleName());
            return null;
        }
    }

    private record Credential(String appId, String secret) {
    }
}
