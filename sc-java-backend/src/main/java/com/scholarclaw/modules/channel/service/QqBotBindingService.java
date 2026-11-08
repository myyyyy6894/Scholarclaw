package com.scholarclaw.modules.channel.service;

import com.scholarclaw.common.config.ScTimeZone;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Web 认证用户发起、QQ 私聊一次性消费的绑定挑战。 */
@Service
public class QqBotBindingService {
    public static final int VALID_SECONDS = 300;
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{32}");
    private final SecureRandom random = new SecureRandom();
    private final UserConfigService userConfigService;

    public QqBotBindingService(UserConfigService userConfigService) {
        this.userConfigService = userConfigService;
    }

    public String issue(Long userId) {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        userConfigService.issueQqBotChallenge(userId, hash(token),
                ScTimeZone.now().toLocalDateTime().plusSeconds(VALID_SECONDS));
        return token;
    }

    public boolean consume(QqBotConnection.InboundMessage message, String token) {
        if (message.group() || token == null || !TOKEN.matcher(token).matches()
                || message.openid() == null || message.openid().isBlank() || message.openid().length() > 128) {
            return false;
        }
        return userConfigService.consumeQqBotChallenge(message.userId(), message.appId(),
                message.connectionVersion(), message.openid(), hash(token));
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
