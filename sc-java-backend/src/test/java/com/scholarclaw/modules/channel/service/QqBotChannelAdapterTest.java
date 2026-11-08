package com.scholarclaw.modules.channel.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.scholarclaw.modules.agent.service.AgentOrchestratorService;
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QqBotChannelAdapterTest {
    private final AgentOrchestratorService agent = mock(AgentOrchestratorService.class);
    private final UserConfigService configs = mock(UserConfigService.class);
    private final QqBotSender sender = mock(QqBotSender.class);
    private final QqBotBindingService binding = mock(QqBotBindingService.class);
    private final QqBotChannelAdapter adapter = new QqBotChannelAdapter(agent, configs, sender, binding);
    private final UserConfigEntity config = new UserConfigEntity();

    @BeforeEach
    void setUp() {
        config.setAccountStatus("ACTIVE");
        config.setQqbotAppId("123456");
        config.setQqbotBindingVersion(7L);
        config.setQqbotOpenid("owner");
        config.setQqbotBindingVerified(1);
        config.setTimezone("Asia/Shanghai");
        lenient().when(configs.findById(1L)).thenReturn(Optional.of(config));
    }

    @Test
    void unverifiedLegacyFirstMessageAndForeignSenderCannotRunAgent() {
        config.setQqbotBindingVerified(0);
        adapter.handle(message("123456", 7L, "owner", "查任务", false));
        config.setQqbotOpenid(null);
        adapter.handle(message("123456", 7L, "attacker", "查任务", false));
        config.setQqbotBindingVerified(1);
        config.setQqbotOpenid("owner");
        adapter.handle(message("123456", 7L, "attacker", "查任务", false));
        verifyNoInteractions(agent, binding, sender);
    }

    @Test
    void staleVersionDifferentBotDeactivationAndGroupAreRejected() {
        adapter.handle(message("123456", 6L, "owner", "查任务", false));
        adapter.handle(message("654321", 7L, "owner", "查任务", false));
        adapter.handle(message("123456", 7L, "owner", "查任务", true));
        config.setAccountStatus("DEACTIVATING");
        adapter.handle(message("123456", 7L, "owner", "查任务", false));
        verifyNoInteractions(agent, binding, sender);
    }

    @Test
    void verifiedPrivateMessageReachesAgent() {
        adapter.handle(message("123456", 7L, "owner", "查任务", false));
        verify(agent).handle(any());
        verifyNoInteractions(binding);
    }

    @Test
    void challengeIsOnlyConsumedPrivatelyAndNeverForwardedToAgent() {
        var message = message("123456", 7L, "owner", "/绑定 abc", false);
        when(binding.consume(message, "abc")).thenReturn(true);
        adapter.handle(message);
        adapter.handle(message("123456", 7L, "owner", "/绑定 abc", true));
        verify(binding).consume(message, "abc");
        verify(sender).reply(eq(1L), eq("123456"), eq(7L), eq("owner"), eq("msg"), eq(1), anyString());
        verifyNoInteractions(agent);
    }

    private static QqBotConnection.InboundMessage message(String app, long version, String openid,
                                                           String text, boolean group) {
        return new QqBotConnection.InboundMessage(1L, app, version, openid, text, "msg", group, null);
    }
}
