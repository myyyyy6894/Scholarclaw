package com.scholarclaw.common.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class QqBotApiClientDeadlineTest {
    @Test
    void tokenRefreshThatReachesClassStartNeverPostsTheMessage() {
        var clock = mock(Clock.class);
        var transport = mock(HttpClient.class);
        var opening = Instant.parse("2026-11-23T00:00:00Z");
        when(clock.instant()).thenReturn(opening.minusSeconds(1), opening);
        var client = spy(new QqBotApiClient(new ObjectMapper(), clock, transport));
        doReturn("fixture-token").when(client).accessToken("fixture-app", "fixture-secret");

        assertThat(client.sendC2cTextBefore("fixture-app", "fixture-secret", "fixture-user", "上课", opening)).isFalse();
        verify(client).accessToken("fixture-app", "fixture-secret");
        verifyNoInteractions(transport);
    }

    @Test
    void expiredMessageSkipsTokenRefresh() {
        var clock = mock(Clock.class);
        var transport = mock(HttpClient.class);
        var opening = Instant.parse("2026-11-23T00:00:00Z");
        when(clock.instant()).thenReturn(opening);
        var client = spy(new QqBotApiClient(new ObjectMapper(), clock, transport));

        assertThat(client.sendC2cTextBefore("fixture-app", "fixture-secret", "fixture-user", "上课", opening)).isFalse();
        verify(client, never()).accessToken(anyString(), anyString());
        verifyNoInteractions(transport);
    }
}
