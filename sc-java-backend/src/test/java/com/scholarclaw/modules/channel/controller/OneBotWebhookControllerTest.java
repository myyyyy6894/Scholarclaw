package com.scholarclaw.modules.channel.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.config.JacksonConfig;
import com.scholarclaw.modules.channel.dto.OneBotAck;
import com.scholarclaw.modules.channel.dto.WebhookDecision;
import com.scholarclaw.modules.channel.service.OneBotWebhookService;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** `API-WEBHOOK-001` 控制器契约（TASK-803）。 */
class OneBotWebhookControllerTest {

    private static final String PATH = "/webhooks/onebot/v11";
    private static final ObjectMapper MAPPER = JacksonConfig
            .applyTo(new org.springframework.http.converter.json.Jackson2ObjectMapperBuilder(),
                    ZoneId.of("Asia/Shanghai")).build();

    private OneBotWebhookService webhookService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        webhookService = mock(OneBotWebhookService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new OneBotWebhookController(webhookService, MAPPER))
                .setMessageConverters(
                        new org.springframework.http.converter.ByteArrayHttpMessageConverter(),
                        new MappingJackson2HttpMessageConverter(MAPPER))
                .build();
    }

    @Test
    @DisplayName("令牌失败：HTTP 401 空响应，不返回业务信封")
    void returnsUnauthorizedWithoutBody() throws Exception {
        when(webhookService.accept(eq("Bearer wrong"), eq(null), any(), any()))
                .thenReturn(WebhookDecision.unauthorized());

        mockMvc.perform(post(PATH)
                        .header("Authorization", "Bearer wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"post_type\":\"message\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));

        verify(webhookService).accept(eq("Bearer wrong"), eq(null), any(), any());
    }

    @Test
    @DisplayName("白名单拒绝：HTTP 200 空响应（OneBot 不应重试）")
    void returnsEmptyOkForRejectedSender() throws Exception {
        when(webhookService.accept(eq("Bearer token"), eq(null), any(), any()))
                .thenReturn(WebhookDecision.emptyOk());

        mockMvc.perform(post(PATH)
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"post_type\":\"message\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("正常/重复：HTTP 200 OneBot ACK，snake_case 字段")
    void returnsOneBotAck() throws Exception {
        when(webhookService.accept(eq("Bearer token"), eq(null), any(), any()))
                .thenReturn(WebhookDecision.ack(new OneBotAck("ok", 0,
                        "DUPLICATE_IGNORED", "tr_81a1b2c3d4e5")));

        mockMvc.perform(post(PATH)
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"post_type\":\"message\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.retcode").value(0))
                .andExpect(jsonPath("$.inbound_status").value("DUPLICATE_IGNORED"))
                .andExpect(jsonPath("$.trace_id").value("tr_81a1b2c3d4e5"))
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    @Test
    @DisplayName("缺少 Authorization 头也交由服务层判定（不会抛 500）")
    void missingAuthorizationIsHandledByService() throws Exception {
        when(webhookService.accept(eq(null), eq(null), any(), any()))
                .thenReturn(WebhookDecision.unauthorized());

        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"post_type\":\"message\"}"))
                .andExpect(status().isUnauthorized());
    }
}
