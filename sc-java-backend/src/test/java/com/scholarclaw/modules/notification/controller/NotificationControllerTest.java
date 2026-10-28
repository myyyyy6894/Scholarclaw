package com.scholarclaw.modules.notification.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.config.JacksonConfig;
import com.scholarclaw.common.exception.GlobalExceptionHandler;
import com.scholarclaw.common.security.ScUserPrincipal;
import com.scholarclaw.modules.notification.dto.MarkReadResultVO;
import com.scholarclaw.modules.notification.dto.NotificationListVO;
import com.scholarclaw.modules.notification.dto.NotificationVO;
import com.scholarclaw.modules.notification.service.NotificationService;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/** `API-NOTIF-001/002` 控制器契约（TASK-802）。 */
class NotificationControllerTest {

    private static final long USER_ID = 10001L;
    private static final ObjectMapper MAPPER = JacksonConfig
            .applyTo(new org.springframework.http.converter.json.Jackson2ObjectMapperBuilder(),
                    ZoneId.of("Asia/Shanghai")).build();

    private NotificationService notificationService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        notificationService = mock(NotificationService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new NotificationController(notificationService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(MAPPER))
                .setValidator(new LocalValidatorFactoryBean())
                .build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new ScUserPrincipal(USER_ID, "zhangsan_cs", "STUDENT", "jti",
                        Instant.now().plusSeconds(60)), null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("默认分页查询：未读数、total_pages 与十进制字符串 ID")
    void listsWithDefaultPagination() throws Exception {
        when(notificationService.list(USER_ID, null, 1, 20)).thenReturn(new NotificationListVO(
                2, 1, 20, 2, 1,
                List.of(new NotificationVO(70002L, "DDL_T24H", 102L,
                        "⏰ [T-24h 提醒] 操作系统原理", "任务 #T102 即将截止。",
                        "UNREAD", "FALLBACK_WEB_ONLY", null, null,
                        OffsetDateTime.parse("2026-10-15T17:00:00.100+08:00")))));

        mockMvc.perform(get("/api/v1/notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.unread_count").value(2))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.total_pages").value(1))
                .andExpect(jsonPath("$.data.items[0].notification_id").value("70002"))
                .andExpect(jsonPath("$.data.items[0].biz_id").value("102"))
                .andExpect(jsonPath("$.data.items[0].biz_type").value("DDL_T24H"))
                .andExpect(jsonPath("$.data.items[0].outbound_status")
                        .value("FALLBACK_WEB_ONLY"))
                .andExpect(jsonPath("$.data.items[0].sent_im_at")
                        .value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.items[0].read_at")
                        .value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.items[0].created_at")
                        .value("2026-10-15T17:00:00.1+08:00"));

        verify(notificationService).list(USER_ID, null, 1, 20);
    }

    @Test
    @DisplayName("带 read_status/page/size 查询时原样传给服务层")
    void passesQueryParameters() throws Exception {
        when(notificationService.list(USER_ID, "UNREAD", 2, 10))
                .thenReturn(new NotificationListVO(0, 2, 10, 0, 0, List.of()));

        mockMvc.perform(get("/api/v1/notifications")
                        .queryParam("read_status", "UNREAD")
                        .queryParam("page", "2")
                        .queryParam("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.total_pages").value(0));

        verify(notificationService).list(USER_ID, "UNREAD", 2, 10);
    }

    @Test
    @DisplayName("标记全部已读：mark_all=true 时忽略 notification_ids")
    void marksAllRead() throws Exception {
        when(notificationService.markRead(USER_ID, true, null))
                .thenReturn(new MarkReadResultVO(3, 0));

        mockMvc.perform(patch("/api/v1/notifications/read")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mark_all\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.message").value("已标记为已读"))
                .andExpect(jsonPath("$.data.updated_count").value(3))
                .andExpect(jsonPath("$.data.remaining_unread_count").value(0));

        verify(notificationService).markRead(USER_ID, true, null);
    }

    @Test
    @DisplayName("标记指定通知：ID 以字符串数组透传")
    void marksSelectedRead() throws Exception {
        when(notificationService.markRead(eq(USER_ID), eq(false), any()))
                .thenReturn(new MarkReadResultVO(2, 1));

        mockMvc.perform(patch("/api/v1/notifications/read")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mark_all\":false,\"notification_ids\":[\"70001\",\"70002\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updated_count").value(2))
                .andExpect(jsonPath("$.data.remaining_unread_count").value(1));

        verify(notificationService).markRead(USER_ID, false, List.of("70001", "70002"));
    }

    @Test
    @DisplayName("mark_all 缺失：400 且不调用服务")
    void rejectsMissingMarkAll() throws Exception {
        mockMvc.perform(patch("/api/v1/notifications/read")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notification_ids\":[\"70001\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(notificationService, never()).markRead(any(), any(), any());
    }

    @Test
    @DisplayName("notification_ids 超过 100 个：400 且不调用服务")
    void rejectsTooManyIds() throws Exception {
        StringBuilder ids = new StringBuilder();
        for (int i = 1; i <= 101; i++) {
            if (i > 1) {
                ids.append(',');
            }
            ids.append('"').append(70000 + i).append('"');
        }
        mockMvc.perform(patch("/api/v1/notifications/read")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mark_all\":false,\"notification_ids\":[" + ids + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(notificationService, never()).markRead(any(), any(), any());
    }
}
