package com.scholarclaw.modules.knowledge.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.config.JacksonConfig;
import com.scholarclaw.common.exception.GlobalExceptionHandler;
import com.scholarclaw.common.security.ScUserPrincipal;
import com.scholarclaw.modules.knowledge.dto.KnowledgeDocVO;
import com.scholarclaw.modules.knowledge.service.KnowledgeDocService;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/** `API-DOC-001` 控制器契约。 */
class KnowledgeDocControllerTest {

    private static final long USER_ID = 10001L;
    private static final long COURSE_ID = 2001L;
    private static final ObjectMapper MAPPER = JacksonConfig
            .applyTo(new org.springframework.http.converter.json.Jackson2ObjectMapperBuilder(),
                    ZoneId.of("Asia/Shanghai")).build();

    private KnowledgeDocService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(KnowledgeDocService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new KnowledgeDocController(service))
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
    @DisplayName("列表返回十进制字符串 ID 与 snake_case 字段")
    void listUsesStringIdsAndSnakeCase() throws Exception {
        when(service.listDocs(USER_ID, COURSE_ID, null)).thenReturn(List.of(doc()));

        mockMvc.perform(get("/api/v1/courses/2001/docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].doc_id").value("50001"))
                .andExpect(jsonPath("$.data[0].course_id").value("2001"))
                .andExpect(jsonPath("$.data[0].file_size_bytes").value(6501171))
                .andExpect(jsonPath("$.data[0].status").value("READY"))
                .andExpect(jsonPath("$.data[0].parse_version").value(1));
    }

    @Test
    @DisplayName("status 过滤参数原样传给服务层")
    void forwardsStatusFilter() throws Exception {
        when(service.listDocs(eq(USER_ID), eq(COURSE_ID), eq("FAILED"))).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/courses/2001/docs").param("status", "FAILED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("非法 course_id 返回 400")
    void rejectsInvalidCourseId() throws Exception {
        mockMvc.perform(get("/api/v1/courses/not-a-number/docs"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    private static KnowledgeDocVO doc() {
        return new KnowledgeDocVO(50001L, COURSE_ID, "第5章_事务处理.pdf", ".pdf", 6501171L,
                "READY", 1, 1, "BAAI/bge-m3", 1024, 38, 112, 0,
                null, null, "2026-10-15T10:31:00.000+08:00",
                "2026-10-15T10:31:24.500+08:00", "2026-10-15T10:30:59.800+08:00",
                "2026-10-15T10:31:24.500+08:00");
    }
}
