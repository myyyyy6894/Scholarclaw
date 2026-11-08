package com.scholarclaw.modules.channel.service;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.client.QqBotApiClient;
import com.scholarclaw.common.event.UserDeactivatingEvent;
import com.scholarclaw.common.security.AesGcmCryptoService;
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

class QqBotConnectionManagerTest {
    @Test
    @SuppressWarnings("unchecked")
    void replacingOrDeactivatingConnectionRejectsLateCallbacks() {
        var configs = mock(UserConfigService.class);
        var crypto = mock(AesGcmCryptoService.class);
        var client = mock(QqBotApiClient.class);
        var adapter = mock(QqBotChannelAdapter.class);
        var current = new AtomicReference<UserConfigEntity>();
        when(configs.saveQqBotConfig(eq(1L), anyString(), anyString())).thenAnswer(inv -> {
            var config = new UserConfigEntity();
            config.setUserId(1L);
            config.setAccountStatus("ACTIVE");
            config.setQqbotAppId(inv.getArgument(1));
            config.setQqbotSecretCipher("cipher");
            config.setQqbotBindingVersion(current.get() == null ? 1L : 2L);
            current.set(config);
            return config;
        });
        when(configs.findById(1L)).thenAnswer(inv -> Optional.ofNullable(current.get()));
        when(crypto.decrypt("cipher")).thenReturn("secret");
        var inbound = new ArrayList<Consumer<QqBotConnection.InboundMessage>>();
        var status = new ArrayList<Consumer<QqBotConnection.StatusChange>>();
        try (MockedConstruction<QqBotConnection> created = mockConstruction(QqBotConnection.class, (mock, context) -> {
            inbound.add((Consumer<QqBotConnection.InboundMessage>) context.arguments().get(6));
            status.add((Consumer<QqBotConnection.StatusChange>) context.arguments().get(7));
        })) {
            var manager = new QqBotConnectionManager(configs, crypto, client, new ObjectMapper(), adapter);
            try {
                manager.configureAndConnect(1L, "123456", "cipher");
                manager.configureAndConnect(1L, "654321", "cipher");
                List<QqBotConnection> connections = created.constructed();
                verify(connections.get(0)).stop();
                clearInvocations(configs);
                status.get(0).accept(new QqBotConnection.StatusChange(1L, "ONLINE", null));
                verify(configs, never()).updateQqBotStatus(anyLong(), anyString(), anyLong(), anyString(), any());
                var oldMessage = new QqBotConnection.InboundMessage(1L, "123456", 1L,
                        "owner", "message", "id", false, null);
                var newMessage = new QqBotConnection.InboundMessage(1L, "654321", 2L,
                        "owner", "message", "id", false, null);
                inbound.get(0).accept(oldMessage);
                inbound.get(1).accept(newMessage);
                verify(adapter, timeout(2000)).handle(newMessage);
                verify(adapter, never()).handle(oldMessage);
                manager.onUserDeactivating(new UserDeactivatingEvent(1L));
                verify(connections.get(1)).stop();
                inbound.get(1).accept(newMessage);
                verify(adapter, after(100).times(1)).handle(newMessage);
            } finally {
                manager.shutdown();
            }
        }
    }
}
