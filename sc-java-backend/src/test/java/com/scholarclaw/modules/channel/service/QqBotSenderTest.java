package com.scholarclaw.modules.channel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.scholarclaw.common.client.QqBotApiClient;
import com.scholarclaw.common.security.AesGcmCryptoService;
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class QqBotSenderTest {
    @Test
    void legacyAndStaleBindingsCannotReceiveProactiveOrPassiveMessages() {
        var configs = mock(UserConfigService.class);
        var crypto = mock(AesGcmCryptoService.class);
        var client = mock(QqBotApiClient.class);
        var sender = new QqBotSender(configs, crypto, client);
        var config = new UserConfigEntity();
        config.setAccountStatus("ACTIVE");
        config.setQqbotAppId("123456");
        config.setQqbotSecretCipher("cipher");
        config.setQqbotBindingVersion(7L);
        config.setQqbotOpenid("old-openid");
        config.setQqbotBindingVerified(0);
        when(configs.findById(1L)).thenReturn(Optional.of(config));
        assertThat(sender.sendProactive(1L, "private reminder")).isFalse();
        config.setQqbotBindingVerified(1);
        sender.reply(1L, "123456", 6L, "old-openid", "msg", 1, "reply");
        sender.reply(1L, "654321", 7L, "old-openid", "msg", 1, "reply");
        verifyNoInteractions(client, crypto);
    }
}
