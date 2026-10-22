package com.scholarclaw.modules.knowledge.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.config.JacksonConfig;
import com.scholarclaw.common.exception.GlobalExceptionHandler;
import com.scholarclaw.modules.knowledge.dto.IngestResultVO;
import com.scholarclaw.modules.knowledge.dto.ParseContextVO;
import com.scholarclaw.modules.knowledge.service.KnowledgeIngestResultService;
import com.scholarclaw.modules.knowledge.service.KnowledgeParseContextService;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class InternalRagCallbackControllerTest {

    private static final long USER_ID = 10001L;
    private static final long DOC_ID = 50001L;
    private static final ObjectMapper MAPPER = JacksonConfig
            .applyTo(new org.springframework.http.converter.json.Jackson2ObjectMapperBuilder(),
                    ZoneId.of("Asia/Shanghai")).build();

    private KnowledgeParseContextService parseContextService;
    private KnowledgeIngestResultService ingestResultService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        parseContextService = mock(KnowledgeParseContextService.class);
        ingestResultService = mock(KnowledgeIngestResultService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new InternalRagCallbackController(parseContextService, ingestResultService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(MAPPER))
                .setValidator(new LocalValidatorFactoryBean())
                .build();
    }

    @Test
    @DisplayName("parse-context 返回 snake_case 与字符串 ID")
    void parseContextUsesSnakeCase() throws Exception {
        when(parseContextService.resolve(USER_ID, DOC_ID, 1, 1)).thenReturn(new ParseContextVO(
                true, null, USER_ID, 2001L, DOC_ID, "a.pdf", ".pdf",
                "raw/10001/2001/50001.pdf", 1, 1,
                new ParseContextVO.EmbeddingConfigVO("OPENAI_COMPATIBLE",
                        "https://example.com/v1", "secret", "embed", 8)));

        mockMvc.perform(get("/internal/api/v1/docs/50001/parse-context")
                        .param("parse_version", "1")
                        .param("course_index_version", "1")
                        .header("X-User-Id", "10001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allow_proceed").value(true))
                .andExpect(jsonPath("$.data.user_id").value("10001"))
                .andExpect(jsonPath("$.data.embedding_config.api_key").value("secret"));
    }

    @Test
    @DisplayName("缺少或非法 X-User-Id 返回 400")
    void rejectsMissingUserId() throws Exception {
        mockMvc.perform(get("/internal/api/v1/docs/50001/parse-context")
                        .param("parse_version", "1")
                        .param("course_index_version", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("ingest-result 返回 applied/current_status")
    void ingestResultReturnsApplied() throws Exception {
        when(ingestResultService.apply(eq(USER_ID), eq(DOC_ID), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new IngestResultVO(DOC_ID, true, "READY", false));

        mockMvc.perform(post("/internal/api/v1/rag/ingest-result")
                        .header("X-User-Id", "10001")
                        .contentType("application/json")
                        .content("""
                                {"ingest_job_id":"job_1","user_id":"10001","course_id":"2001",
                                 "doc_id":"50001","parse_version":1,"course_index_version":1,
                                 "status":"READY","indexed_embedding_provider":"OPENAI_COMPATIBLE",
                                 "indexed_embedding_model":"embed","indexed_embedding_dim":8,
                                 "page_count":1,"chunk_count":1,
                                 "parent_chunks":[{"parent_chunk_id":"pc_50001_v1_1","chunk_seq":1,
                                   "page_start":1,"page_end":1,"section_title":null,
                                   "content":"内容","char_length":2}],
                                 "error_code":null,"error_msg":null}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.doc_id").value("50001"))
                .andExpect(jsonPath("$.data.applied").value(true))
                .andExpect(jsonPath("$.data.current_status").value("READY"));
    }
}
