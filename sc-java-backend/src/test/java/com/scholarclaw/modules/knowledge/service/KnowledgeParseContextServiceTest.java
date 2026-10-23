package com.scholarclaw.modules.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.common.security.AesGcmCryptoService;
import com.scholarclaw.modules.course.entity.CourseNamespaceEntity;
import com.scholarclaw.modules.course.service.CourseService;
import com.scholarclaw.modules.knowledge.dto.ParseContextVO;
import com.scholarclaw.modules.knowledge.entity.KnowledgeDocEntity;
import com.scholarclaw.modules.knowledge.mapper.KnowledgeDocMapper;
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import com.scholarclaw.modules.userconfig.service.ModelConfigRules;
import com.scholarclaw.modules.userconfig.service.ModelKeyBindingRules;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KnowledgeParseContextServiceTest {

    private static final long USER_ID = 10001L;
    private static final long COURSE_ID = 2001L;
    private static final long DOC_ID = 50001L;

    private KnowledgeDocMapper docMapper;
    private KnowledgeFailureService failureService;
    private CourseService courseService;
    private UserConfigService userConfigService;
    private AesGcmCryptoService cryptoService;
    private KnowledgeParseContextService service;

    @BeforeEach
    void setUp() {
        docMapper = mock(KnowledgeDocMapper.class);
        failureService = mock(KnowledgeFailureService.class);
        courseService = mock(CourseService.class);
        userConfigService = mock(UserConfigService.class);
        cryptoService = mock(AesGcmCryptoService.class);
        service = new KnowledgeParseContextService(docMapper, courseService,
                userConfigService, new ModelConfigRules(false, ""),
                new ModelKeyBindingRules(), cryptoService, failureService);
    }

    @Test
    @DisplayName("版本/状态匹配时返回解密后的课程 Embedding 配置")
    void returnsEmbeddingConfigWhenValid() {
        when(docMapper.selectById(DOC_ID)).thenReturn(doc("PARSING"));
        when(courseService.getActiveCourse(USER_ID, COURSE_ID)).thenReturn(course());
        when(userConfigService.findById(USER_ID)).thenReturn(Optional.of(config()));
        when(cryptoService.decrypt("v1:iv:cipher")).thenReturn("sk-embedding");

        ParseContextVO context = service.resolve(USER_ID, DOC_ID, 1, 1);

        assertThat(context.allowProceed()).isTrue();
        assertThat(context.embeddingConfig().provider()).isEqualTo("OPENAI_COMPATIBLE");
        assertThat(context.embeddingConfig().apiKey()).isEqualTo("sk-embedding");
        assertThat(context.embeddingConfig().dim()).isEqualTo(1024);
    }

    @Test
    @DisplayName("旧 parse_version 返回 allow_proceed=false")
    void abortsStaleVersion() {
        when(docMapper.selectById(DOC_ID)).thenReturn(doc("PARSING"));

        ParseContextVO context = service.resolve(USER_ID, DOC_ID, 2, 1);

        assertThat(context.allowProceed()).isFalse();
        assertThat(context.abortReason()).isEqualTo("STALE_PARSE_VERSION");
    }

    @Test
    @DisplayName("DELETING 文档拒绝继续解析")
    void abortsDeletingDoc() {
        when(docMapper.selectById(DOC_ID)).thenReturn(doc("DELETING"));

        assertThat(service.resolve(USER_ID, DOC_ID, 1, 1).abortReason()).isEqualTo("DOC_DELETING");
    }

    @Test
    @DisplayName("Embedding 配置缺失时置 FAILED 并拒绝")
    void failsDocWhenEmbeddingMissing() {
        KnowledgeDocEntity doc = doc("PARSING");
        when(docMapper.selectById(DOC_ID)).thenReturn(doc);
        when(courseService.getActiveCourse(USER_ID, COURSE_ID)).thenReturn(course());
        UserConfigEntity config = config();
        config.setEmbeddingApiKeyCipher(null);
        when(userConfigService.findById(USER_ID)).thenReturn(Optional.of(config));

        ParseContextVO context = service.resolve(USER_ID, DOC_ID, 1, 1);

        assertThat(context.allowProceed()).isFalse();
        assertThat(context.abortReason()).isEqualTo("EMBEDDING_CONFIG_MISSING");
        verify(failureService).fail(eq(USER_ID), eq(COURSE_ID), eq(DOC_ID), eq(1), eq(1),
                eq("EMBEDDING_CONFIG_MISSING"), any(String.class));
    }

    @Test
    @DisplayName("跨用户读取文档返回 404")
    void rejectsDifferentUser() {
        when(docMapper.selectById(DOC_ID)).thenReturn(doc("PARSING"));

        assertThatThrownBy(() -> service.resolve(99999L, DOC_ID, 1, 1))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        verify(failureService, never()).fail(any(), any(), any(), anyInt(), anyInt(), any(), any());
    }

    private static KnowledgeDocEntity doc(String status) {
        KnowledgeDocEntity doc = new KnowledgeDocEntity();
        doc.setDocId(DOC_ID);
        doc.setUserId(USER_ID);
        doc.setCourseId(COURSE_ID);
        doc.setDocName("a.pdf");
        doc.setFileExt(".pdf");
        doc.setStorageRelPath("raw/10001/2001/50001.pdf");
        doc.setStatus(status);
        doc.setParseVersion(1);
        doc.setCourseIndexVersion(1);
        return doc;
    }

    private static CourseNamespaceEntity course() {
        CourseNamespaceEntity course = new CourseNamespaceEntity();
        course.setCourseId(COURSE_ID);
        course.setUserId(USER_ID);
        course.setEmbeddingProvider("OPENAI_COMPATIBLE");
        course.setEmbeddingModel("BAAI/bge-m3");
        course.setEmbeddingDim(1024);
        course.setIndexVersion(1);
        course.setIndexStatus("ACTIVE");
        course.setIsDeleting(0);
        return course;
    }

    private static UserConfigEntity config() {
        UserConfigEntity config = new UserConfigEntity();
        config.setUserId(USER_ID);
        config.setEmbeddingBaseUrl("https://api.siliconflow.cn/v1");
        config.setDefaultEmbeddingProvider("OPENAI_COMPATIBLE");
        config.setDefaultEmbeddingModel("BAAI/bge-m3");
        config.setEmbeddingApiKeyCipher("v1:iv:cipher");
        return config;
    }
}
