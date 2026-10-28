package com.scholarclaw.modules.channel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.enums.InboundStatus;
import com.scholarclaw.modules.channel.dto.WebhookDecision;
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

/** TASK-803 Webhook 同步网关：鉴权、白名单、去重、快速 ACK 与异步提交。 */
class OneBotWebhookServiceTest {

    private static final String TOKEN = "secret-token";
    private static final String SENDER = "24681012";
    private static final String MESSAGE_ID = "188992211";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private UserConfigService userConfigService;
    private OneBotInboundDedup dedup;
    private OneBotChannelAdapter channelAdapter;
    private OneBotWebhookService service;

    @BeforeEach
    void setUp() {
        userConfigService = mock(UserConfigService.class);
        dedup = mock(OneBotInboundDedup.class);
        channelAdapter = mock(OneBotChannelAdapter.class);
        service = new OneBotWebhookService(
                new OneBotAuthVerifier(TOKEN),
                new OneBotEventParser(),
                userConfigService,
                dedup,
                channelAdapter,
                Runnable::run,
                "");
        when(userConfigService.findActiveByBoundIm("QQ_ONEBOT", SENDER))
                .thenReturn(Optional.of(user(10001L)));
        when(dedup.claim(SENDER, MESSAGE_ID))
                .thenReturn(new OneBotInboundDedup.Claim(true, "owner-token"));
    }

    @Test
    @DisplayName("令牌错误：401，且不查绑定/不去重/不处理")
    void rejectsWrongToken() {
        WebhookDecision decision = service.accept("Bearer wrong", privateText());

        assertThat(decision.httpStatus()).isEqualTo(401);
        assertThat(decision.ack()).isNull();
        verify(userConfigService, never()).findActiveByBoundIm(anyString(), anyString());
        verify(dedup, never()).claim(anyString(), anyString());
        verify(channelAdapter, never()).process(any(), any());
    }

    @Test
    @DisplayName("心跳事件：200 RECEIVED，不进入业务")
    void acknowledgesMetaEvent() throws Exception {
        WebhookDecision decision = service.accept("Bearer " + TOKEN, json("""
                {"post_type":"meta_event","meta_event_type":"heartbeat","self_id":987654321}
                """));

        assertThat(decision.httpStatus()).isEqualTo(200);
        assertThat(decision.ack().inboundStatus()).isEqualTo(InboundStatus.RECEIVED.name());
        verify(channelAdapter, never()).process(any(), any());
    }

    @Test
    @DisplayName("未绑定 ACTIVE 用户：HTTP 200 空响应，静默丢弃")
    void rejectsUnboundSender() {
        when(userConfigService.findActiveByBoundIm("QQ_ONEBOT", SENDER))
                .thenReturn(Optional.empty());

        WebhookDecision decision = service.accept("Bearer " + TOKEN, privateText());

        assertThat(decision.httpStatus()).isEqualTo(200);
        assertThat(decision.ack()).isNull();
        verify(dedup, never()).claim(anyString(), anyString());
        verify(channelAdapter, never()).process(any(), any());
    }

    @Test
    @DisplayName("环境变量发送者白名单未命中：HTTP 200 空响应")
    void rejectsSenderOutsideEnvAllowlist() {
        OneBotWebhookService allowlisted = new OneBotWebhookService(
                new OneBotAuthVerifier(TOKEN), new OneBotEventParser(), userConfigService,
                dedup, channelAdapter, Runnable::run, "111111,222222");

        WebhookDecision decision = allowlisted.accept("Bearer " + TOKEN, privateText());

        assertThat(decision.httpStatus()).isEqualTo(200);
        assertThat(decision.ack()).isNull();
        verify(userConfigService, never()).findActiveByBoundIm(anyString(), anyString());
    }

    @Test
    @DisplayName("重复消息：200 DUPLICATE_IGNORED，不再次处理")
    void acknowledgesDuplicateWithoutProcessing() {
        when(dedup.claim(SENDER, MESSAGE_ID))
                .thenReturn(new OneBotInboundDedup.Claim(false, null));

        WebhookDecision decision = service.accept("Bearer " + TOKEN, privateText());

        assertThat(decision.ack().inboundStatus())
                .isEqualTo(InboundStatus.DUPLICATE_IGNORED.name());
        verify(channelAdapter, never()).process(any(), any());
    }

    @Test
    @DisplayName("合法私聊文本：快速 ACK RECEIVED，异步处理并标记 PROCESSED")
    void acceptsAndProcessesPrivateText() {
        WebhookDecision decision = service.accept("Bearer " + TOKEN, privateText());

        assertThat(decision.ack().inboundStatus()).isEqualTo(InboundStatus.RECEIVED.name());
        assertThat(decision.ack().traceId()).startsWith("tr_");
        verify(channelAdapter).process(any(), eq(10001L));
        verify(dedup).markProcessed(eq(SENDER), eq(MESSAGE_ID), any());
    }

    @Test
    @DisplayName("异步处理失败：释放去重键，ACK 仍为 RECEIVED（响应已先返回）")
    void releasesDedupOnAsyncFailure() {
        doThrow(new IllegalStateException("boom"))
                .when(channelAdapter).process(any(), any());

        WebhookDecision decision = service.accept("Bearer " + TOKEN, privateText());

        assertThat(decision.ack().inboundStatus()).isEqualTo(InboundStatus.RECEIVED.name());
        verify(dedup).release(eq(SENDER), eq(MESSAGE_ID), any());
        verify(dedup, never()).markProcessed(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Redis 降级放行：仍处理且无 owner token 时不报错")
    void processesWhenDedupDegraded() {
        OneBotInboundDedup.Claim degraded = new OneBotInboundDedup.Claim(true, null);
        when(dedup.claim(SENDER, MESSAGE_ID)).thenReturn(degraded);

        WebhookDecision decision = service.accept("Bearer " + TOKEN, privateText());

        assertThat(decision.ack().inboundStatus()).isEqualTo(InboundStatus.RECEIVED.name());
        verify(channelAdapter).process(any(), eq(10001L));
    }

    private JsonNode privateText() {
        try {
            return json("""
                    {"post_type":"message","message_type":"private","message_id":188992211,
                     "user_id":24681012,"time":1760509200,
                     "message":[{"type":"text","data":{"text":"数据库作业 10月25日交"}}]}
                    """);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode json(String value) throws Exception {
        return new ObjectMapper().readTree(value);
    }

    private static UserConfigEntity user(long userId) {
        UserConfigEntity user = new UserConfigEntity();
        user.setUserId(userId);
        user.setAccountStatus("ACTIVE");
        user.setTimezone("Asia/Shanghai");
        return user;
    }
}
