package com.scholarclaw.modules.knowledge.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.config.JacksonConfig;
import com.scholarclaw.common.exception.GlobalExceptionHandler;
import com.scholarclaw.common.security.ScUserPrincipal;
import com.scholarclaw.modules.knowledge.dto.KnowledgeDocVO;
import com.scholarclaw.modules.knowledge.entity.KnowledgeDocEntity;
import com.scholarclaw.modules.knowledge.service.KnowledgeDeletionService;
import com.scholarclaw.modules.knowledge.service.KnowledgeDocService;
import com.scholarclaw.modules.knowledge.service.KnowledgeIngestService;
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

/** `API-DOC-003/004/005` 控制器契约。 */
class KnowledgeDocActionControllerTest {

    private static final long USER_ID = 10001L;
    private static final long DOC_ID = 50001L;
    private static final ObjectMapper MAPPER = JacksonConfig
            .applyTo(new org.springframework.http.converter.json.Jackson2ObjectMapperBuilder(),
                    ZoneId.of("Asia/Shanghai")).build();

    private KnowledgeDocService docService;
    private KnowledgeIngestService ingestService;
    private KnowledgeDeletionService deletionService;
    private com.scholarclaw.modules.course.service.CourseService courseService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        docService = mock(KnowledgeDocService.class);
        ingestService = mock(KnowledgeIngestService.class);
        deletionService = mock(KnowledgeDeletionService.class);
        courseService = mock(com.scholarclaw.modules.course.service.CourseService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new KnowledgeDocActionController(docService, ingestService,
                                deletionService, courseService, true))
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
    @DisplayName("retry 返回 PARSING 文档")
    void retryReturnsParsing() throws Exception {
        when(ingestService.retry(USER_ID, DOC_ID)).thenReturn(vo());

        mockMvc.perform(post("/api/v1/docs/50001/retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.doc_id").value("50001"))
                .andExpect(jsonPath("$.data.status").value("PARSING"));
    }

    @Test
    @DisplayName("reindex 返回 PARSING 文档")
    void reindexReturnsParsing() throws Exception {
        when(ingestService.reindex(USER_ID, DOC_ID)).thenReturn(vo());

        mockMvc.perform(post("/api/v1/docs/50001/reindex"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PARSING"));
    }

    @Test
    @DisplayName("P4 门禁关闭时 retry 返回 501 且不修改状态")
    void retryBlockedWhenManualIngestDisabled() throws Exception {
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new KnowledgeDocActionController(docService, ingestService,
                                deletionService, courseService, false))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(MAPPER))
                .setValidator(new LocalValidatorFactoryBean())
                .build();

        mockMvc.perform(post("/api/v1/docs/50001/retry"))
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.code").value("FEATURE_NOT_AVAILABLE"));
        org.mockito.Mockito.verify(ingestService, org.mockito.Mockito.never())
                .retry(USER_ID, DOC_ID);
    }

    @Test
    @DisplayName("delete 完成第二阶段后返回 DELETED")
    void deleteReturnsDeletedAfterFinalize() throws Exception {
        KnowledgeDocEntity deleted = doc();
        deleted.setStatus("DELETING");
        when(docService.softDelete(USER_ID, DOC_ID)).thenReturn(deleted);
        when(deletionService.finalizeDocumentDeletion(USER_ID, deleted, null)).thenReturn(true);
        KnowledgeDocEntity finalized = doc();
        finalized.setStatus("DELETED");
        when(docService.requireOwnedDoc(USER_ID, DOC_ID)).thenReturn(finalized);

        mockMvc.perform(delete("/api/v1/docs/50001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleted_doc_id").value("50001"))
                .andExpect(jsonPath("$.data.status").value("DELETED"));
        org.mockito.Mockito.verify(deletionService)
                .finalizeDocumentDeletion(USER_ID, deleted, null);
        org.mockito.Mockito.verify(courseService)
                .refreshIndexStatus(USER_ID, 2001L, 1);
    }

    @Test
    @DisplayName("非法 doc_id 返回 400")
    void rejectsInvalidDocId() throws Exception {
        mockMvc.perform(post("/api/v1/docs/abc/retry"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    private static KnowledgeDocEntity doc() {
        KnowledgeDocEntity doc = new KnowledgeDocEntity();
        doc.setDocId(DOC_ID);
        doc.setCourseId(2001L);
        doc.setStatus("PARSING");
        return doc;
    }

    private static KnowledgeDocVO vo() {
        return new KnowledgeDocVO(DOC_ID, 2001L, "a.pdf", ".pdf", 10, "PARSING",
                2, 1, null, null, 0, 0, 0, null, null, null, null, null,
                "2026-10-21T10:00:00.000+08:00", "2026-10-21T10:00:00.000+08:00");
    }
}
