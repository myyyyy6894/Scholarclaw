package com.scholarclaw.modules.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scholarclaw.common.enums.DocumentStatus;
import com.scholarclaw.common.enums.IndexStatus;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.course.entity.CourseNamespaceEntity;
import com.scholarclaw.modules.course.service.CourseService;
import com.scholarclaw.modules.knowledge.dto.KnowledgeDocVO;
import com.scholarclaw.modules.knowledge.entity.KnowledgeDocEntity;
import com.scholarclaw.modules.knowledge.mapper.KnowledgeDocMapper;
import com.scholarclaw.modules.knowledge.mapper.KnowledgeParentChunkMapper;
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import com.scholarclaw.modules.userconfig.service.ModelConfigRules;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TASK-402 文档元数据、状态机与乐观锁。 */
class KnowledgeDocServiceTest {

    private static final long USER_ID = 10001L;
    private static final long COURSE_ID = 2001L;
    private static final long DOC_ID = 50001L;

    private KnowledgeDocMapper docMapper;
    private KnowledgeParentChunkMapper parentChunkMapper;
    private CourseService courseService;
    private UserConfigService userConfigService;
    private KnowledgeDocService service;

    @BeforeEach
    void setUp() {
        docMapper = mock(KnowledgeDocMapper.class);
        parentChunkMapper = mock(KnowledgeParentChunkMapper.class);
        courseService = mock(CourseService.class);
        userConfigService = mock(UserConfigService.class);
        service = new KnowledgeDocService(docMapper, parentChunkMapper, courseService,
                userConfigService, new ModelConfigRules(false, ""));
        when(userConfigService.findById(USER_ID)).thenReturn(Optional.of(validConfig()));
    }

    @Test
    @DisplayName("列表按当前用户与课程过滤，并映射为十进制字符串 ID")
    void listsDocsForCurrentUserAndCourse() {
        when(courseService.getActiveCourse(USER_ID, COURSE_ID)).thenReturn(course(IndexStatus.ACTIVE));
        when(docMapper.selectList(any())).thenReturn(List.of(doc(DocumentStatus.READY, 1)));

        List<KnowledgeDocVO> docs = service.listDocs(USER_ID, COURSE_ID, "READY");

        assertThat(docs).hasSize(1);
        assertThat(docs.get(0).docId()).isEqualTo(DOC_ID);
        assertThat(docs.get(0).courseId()).isEqualTo(COURSE_ID);
        assertThat(docs.get(0).status()).isEqualTo("READY");
        assertThat(docs.get(0).indexedEmbeddingDim()).isEqualTo(1024);
    }

    @Test
    @DisplayName("非 READY 文档即使残留旧快照，响应也必须为 null/0")
    void hidesStaleSnapshotForNonReadyDocs() {
        KnowledgeDocEntity parsing = doc(DocumentStatus.PARSING, 2);
        parsing.setIndexedEmbeddingProvider("OPENAI_COMPATIBLE");
        parsing.setIndexedEmbeddingModel("old-model");
        parsing.setIndexedEmbeddingDim(1024);
        parsing.setPageCount(38);
        parsing.setChunkCount(112);
        parsing.setReadyAt(LocalDateTime.now());

        KnowledgeDocVO vo = service.toVO(parsing);

        assertThat(vo.indexedEmbeddingModel()).isNull();
        assertThat(vo.indexedEmbeddingDim()).isNull();
        assertThat(vo.pageCount()).isZero();
        assertThat(vo.chunkCount()).isZero();
        assertThat(vo.readyAt()).isNull();
        assertThat(vo.status()).isEqualTo("PARSING");
    }

    @Test
    @DisplayName("READY 文档保留真实索引快照")
    void exposesSnapshotForReadyDocs() {
        KnowledgeDocEntity ready = doc(DocumentStatus.READY, 1);
        ready.setReadyAt(LocalDateTime.now());
        KnowledgeDocVO vo = service.toVO(ready);

        assertThat(vo.indexedEmbeddingModel()).isEqualTo("BAAI/bge-m3");
        assertThat(vo.indexedEmbeddingDim()).isEqualTo(1024);
        assertThat(vo.pageCount()).isEqualTo(38);
        assertThat(vo.chunkCount()).isEqualTo(112);
        assertThat(vo.readyAt()).isNotNull();
    }

    @Test
    @DisplayName("非法 status 过滤值返回 VALIDATION_ERROR")
    void rejectsInvalidStatusFilter() {
        when(courseService.getActiveCourse(USER_ID, COURSE_ID)).thenReturn(course(IndexStatus.ACTIVE));

        assertThatThrownBy(() -> service.listDocs(USER_ID, COURSE_ID, "ARCHIVED"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
        verify(docMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("markParsing 只在 UPLOADED 且 parse_version 匹配时成功")
    void marksParsingOnlyForMatchingVersionAndState() {
        when(docMapper.update(any(), any())).thenReturn(1, 0);

        assertThat(service.markParsing(DOC_ID, 1)).isTrue();
        assertThat(service.markParsing(DOC_ID, 1)).isFalse();
    }

    @Test
    @DisplayName("markReady 必须匹配 parse_version 与 course_index_version")
    void marksReadyOnlyForMatchingVersions() {
        when(docMapper.update(any(), any())).thenReturn(0);

        boolean updated = service.markReady(DOC_ID, 1, 1, 38, 112,
                "OPENAI_COMPATIBLE", "BAAI/bge-m3", 1024);

        assertThat(updated).isFalse();
    }

    @Test
    @DisplayName("markFailed 对旧 parse_version 返回 false")
    void marksFailedOnlyForMatchingVersion() {
        when(docMapper.update(any(), any())).thenReturn(0);

        assertThat(service.markFailed(DOC_ID, 1, "SCAN_PDF_OCR_NOT_ENABLED", "扫描件"))
                .isFalse();
    }

    @Test
    @DisplayName("retry 仅允许 FAILED，成功后 parse_version+1 并绑定课程当前版本")
    void retriesOnlyFailedDoc() {
        KnowledgeDocEntity failed = doc(DocumentStatus.FAILED, 2);
        KnowledgeDocEntity parsing = doc(DocumentStatus.PARSING, 3);
        parsing.setCourseIndexVersion(4);
        when(docMapper.selectOne(any())).thenReturn(failed, parsing);
        when(courseService.getActiveCourse(USER_ID, COURSE_ID)).thenReturn(course(IndexStatus.ACTIVE));
        when(docMapper.update(any(), any())).thenReturn(1);

        KnowledgeDocEntity result = service.retryFailed(USER_ID, DOC_ID);

        assertThat(result.getStatus()).isEqualTo("PARSING");
        assertThat(result.getParseVersion()).isEqualTo(3);
        assertThat(result.getCourseIndexVersion()).isEqualTo(4);
    }

    @Test
    @DisplayName("READY 文档不能走 retry，返回 STATE_CONFLICT 且不更新")
    void rejectsRetryForReadyDoc() {
        when(docMapper.selectOne(any())).thenReturn(doc(DocumentStatus.READY, 1));

        assertThatThrownBy(() -> service.retryFailed(USER_ID, DOC_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.STATE_CONFLICT));
        verify(docMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("reindex 仅允许 READY")
    void reindexesOnlyReadyDoc() {
        when(docMapper.selectOne(any())).thenReturn(doc(DocumentStatus.READY, 1), doc(DocumentStatus.PARSING, 2));
        when(courseService.getActiveCourse(USER_ID, COURSE_ID)).thenReturn(course(IndexStatus.ACTIVE));
        when(docMapper.update(any(), any())).thenReturn(1);

        assertThat(service.reindexReady(USER_ID, DOC_ID).getStatus()).isEqualTo("PARSING");
    }

    @Test
    @DisplayName("删除文档置 DELETING、递增 parse_version，并在同一事务清空父块")
    void softDeleteMarksDeletingAndClearsParentChunks() {
        KnowledgeDocEntity ready = doc(DocumentStatus.READY, 1);
        KnowledgeDocEntity deleting = doc(DocumentStatus.DELETING, 2);
        when(docMapper.selectOne(any())).thenReturn(ready, deleting);
        when(docMapper.update(any(), any())).thenReturn(1);

        KnowledgeDocEntity result = service.softDelete(USER_ID, DOC_ID);

        assertThat(result.getStatus()).isEqualTo("DELETING");
        verify(parentChunkMapper).deleteByDoc(USER_ID, DOC_ID);
    }

    @Test
    @DisplayName("跨用户删除返回 404，不触发任何更新")
    void softDeleteRejectsOtherUsersDoc() {
        when(docMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.softDelete(USER_ID, DOC_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        verify(docMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("READY 重建进入 PARSING 时显式清空索引快照")
    void clearsIndexSnapshotOnReindex() {
        when(docMapper.selectOne(any())).thenReturn(doc(DocumentStatus.READY, 1));
        when(courseService.getActiveCourse(USER_ID, COURSE_ID)).thenReturn(course(IndexStatus.ACTIVE));
        when(docMapper.update(any(), any())).thenReturn(1);

        service.reindexReady(USER_ID, DOC_ID);

        var wrapper = org.mockito.ArgumentCaptor.forClass(
                com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(docMapper).update(any(), wrapper.capture());
        String sqlSet = String.valueOf(wrapper.getValue().getSqlSet());
        assertThat(sqlSet).contains("indexed_embedding_model = NULL")
                .contains("indexed_embedding_dim = NULL")
                .contains("page_count = 0")
                .contains("chunk_count = 0")
                .contains("ready_at = NULL");
    }

    @Test
    @DisplayName("建课索引 REBUILDING 时拒绝创建文档元数据")
    void rejectsCreateWhileCourseRebuilding() {
        CourseNamespaceEntity rebuilding = course(IndexStatus.REBUILDING);

        assertThatThrownBy(() -> service.createDocumentMetadata(
                rebuilding, "a.pdf", ".pdf", 10, "sha", "tmp"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.E_SYS_03));
        verify(docMapper, never()).insert(any(KnowledgeDocEntity.class));
    }

    private static UserConfigEntity validConfig() {
        UserConfigEntity config = new UserConfigEntity();
        config.setUserId(USER_ID);
        config.setAccountStatus("ACTIVE");
        config.setDefaultEmbeddingProvider("OPENAI_COMPATIBLE");
        config.setEmbeddingBaseUrl("https://api.siliconflow.cn/v1");
        config.setDefaultEmbeddingModel("BAAI/bge-m3");
        config.setDefaultEmbeddingDim(1024);
        config.setEmbeddingApiKeyCipher("v1:iv:cipher");
        return config;
    }

    private static CourseNamespaceEntity course(IndexStatus status) {
        CourseNamespaceEntity course = new CourseNamespaceEntity();
        course.setCourseId(COURSE_ID);
        course.setUserId(USER_ID);
        course.setIndexVersion(4);
        course.setIndexStatus(status.name());
        course.setIsDeleting(0);
        return course;
    }

    private static KnowledgeDocEntity doc(DocumentStatus status, int parseVersion) {
        KnowledgeDocEntity doc = new KnowledgeDocEntity();
        doc.setDocId(DOC_ID);
        doc.setUserId(USER_ID);
        doc.setCourseId(COURSE_ID);
        doc.setDocName("第5章_事务处理.pdf");
        doc.setFileExt(".pdf");
        doc.setFileSizeBytes(6501171L);
        doc.setFileSha256("sha");
        doc.setStorageRelPath("raw/10001/2001/50001.pdf");
        doc.setStatus(status.name());
        doc.setParseVersion(parseVersion);
        doc.setCourseIndexVersion(1);
        doc.setIndexedEmbeddingModel("BAAI/bge-m3");
        doc.setIndexedEmbeddingDim(1024);
        doc.setPageCount(38);
        doc.setChunkCount(112);
        doc.setRetryCount(0);
        doc.setCreatedAt(LocalDateTime.now());
        doc.setUpdatedAt(LocalDateTime.now());
        return doc;
    }
}
