package com.scholarclaw.modules.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scholarclaw.common.event.DocumentVectorCleanupEvent;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.course.entity.CourseNamespaceEntity;
import com.scholarclaw.modules.course.service.CourseService;
import com.scholarclaw.modules.knowledge.dto.IngestResultRequest;
import com.scholarclaw.modules.knowledge.dto.IngestResultVO;
import com.scholarclaw.modules.knowledge.entity.KnowledgeDocEntity;
import com.scholarclaw.modules.knowledge.entity.KnowledgeParentChunkEntity;
import com.scholarclaw.modules.knowledge.mapper.KnowledgeDocMapper;
import com.scholarclaw.modules.knowledge.mapper.KnowledgeParentChunkMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

class KnowledgeIngestResultServiceTest {

    private static final long USER_ID = 10001L;
    private static final long COURSE_ID = 2001L;
    private static final long DOC_ID = 50001L;

    private KnowledgeDocMapper docMapper;
    private KnowledgeParentChunkMapper parentChunkMapper;
    private KnowledgeFailureService failureService;
    private CourseService courseService;
    private ApplicationEventPublisher eventPublisher;
    private KnowledgeIngestResultService service;

    @BeforeEach
    void setUp() {
        docMapper = mock(KnowledgeDocMapper.class);
        parentChunkMapper = mock(KnowledgeParentChunkMapper.class);
        failureService = mock(KnowledgeFailureService.class);
        courseService = mock(CourseService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new KnowledgeIngestResultService(docMapper, parentChunkMapper,
                courseService, failureService, eventPublisher);
    }

    @Test
    @DisplayName("READY 回调原子更新文档并先删后插父块")
    void appliesReadyResult() {
        KnowledgeDocEntity parsing = doc("PARSING");
        KnowledgeDocEntity ready = doc("READY");
        when(docMapper.selectById(DOC_ID)).thenReturn(parsing, ready);
        when(courseService.getActiveCourse(USER_ID, COURSE_ID)).thenReturn(course());
        when(docMapper.update(any(), any())).thenReturn(1);

        IngestResultVO result = service.apply(USER_ID, DOC_ID, request("READY"));

        assertThat(result.applied()).isTrue();
        assertThat(result.currentStatus()).isEqualTo("READY");
        verify(parentChunkMapper).deleteByDoc(USER_ID, DOC_ID);
        ArgumentCaptor<KnowledgeParentChunkEntity> chunk =
                ArgumentCaptor.forClass(KnowledgeParentChunkEntity.class);
        verify(parentChunkMapper).insert(chunk.capture());
        assertThat(chunk.getValue().getParentChunkId()).isEqualTo("pc_50001_v1_1");
        assertThat(chunk.getValue().getCharLength()).isEqualTo(4);
        assertThat(chunk.getValue().getUserId()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("FAILED 回调走统一失败收敛（清父块/向量/课程）")
    void appliesFailedResult() {
        when(docMapper.selectById(DOC_ID)).thenReturn(doc("PARSING"));
        when(failureService.fail(USER_ID, COURSE_ID, DOC_ID, 1, 1, "E-RAG-01", "扫描件"))
                .thenReturn(new KnowledgeFailureService.FailureOutcome(true, false, "FAILED"));

        IngestResultVO result = service.apply(USER_ID, DOC_ID, request("FAILED"));

        assertThat(result.applied()).isTrue();
        verify(failureService).fail(USER_ID, COURSE_ID, DOC_ID, 1, 1, "E-RAG-01", "扫描件");
        verify(parentChunkMapper, never()).deleteByDoc(any(), any());
    }

    @Test
    @DisplayName("读取后并发升级版本导致 UPDATE=0：当前读重新判断并清理迟到向量")
    void cleansLateVersionWhenConditionalUpdateLosesRace() {
        KnowledgeDocEntity parsingV1 = doc("PARSING");
        when(docMapper.selectById(DOC_ID)).thenReturn(parsingV1);
        when(courseService.getActiveCourse(USER_ID, COURSE_ID)).thenReturn(course());
        when(docMapper.update(any(), any())).thenReturn(0);
        when(failureService.inspectStale(USER_ID, DOC_ID, 1, 1))
                .thenReturn(new KnowledgeFailureService.FailureOutcome(false, true, "PARSING"));

        IngestResultVO result = service.apply(USER_ID, DOC_ID, request("READY"));

        assertThat(result.applied()).isFalse();
        assertThat(result.staleVectorCleanupTriggered()).isTrue();
        verify(failureService).inspectStale(USER_ID, DOC_ID, 1, 1);
    }

    @Test
    @DisplayName("失败收敛因并发版本接管返回 false 时使用同一当前读结果")
    void cleansLateVersionWhenFailedConvergenceLosesRace() {
        KnowledgeDocEntity parsingV1 = doc("PARSING");
        when(docMapper.selectById(DOC_ID)).thenReturn(parsingV1);
        when(failureService.fail(USER_ID, COURSE_ID, DOC_ID, 1, 1, "E-RAG-01", "扫描件"))
                .thenReturn(new KnowledgeFailureService.FailureOutcome(false, true, "PARSING"));

        IngestResultVO result = service.apply(USER_ID, DOC_ID, request("FAILED"));

        assertThat(result.applied()).isFalse();
        assertThat(result.staleVectorCleanupTriggered()).isTrue();
    }

    @Test
    @DisplayName("旧版本回调：applied=false 且触发指定版本向量清理")
    void triggersCleanupForStaleVersion() {
        KnowledgeDocEntity current = doc("PARSING");
        current.setParseVersion(2);
        when(docMapper.selectById(DOC_ID)).thenReturn(current);

        IngestResultVO result = service.apply(USER_ID, DOC_ID, request("READY"));

        assertThat(result.applied()).isFalse();
        assertThat(result.staleVectorCleanupTriggered()).isTrue();
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(event.capture());
        DocumentVectorCleanupEvent cleanup = (DocumentVectorCleanupEvent) event.getValue();
        assertThat(cleanup.docId()).isEqualTo(DOC_ID);
        assertThat(cleanup.parseVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("文档 DELETING 时迟到回调：触发向量清理")
    void triggersCleanupForDeletedDoc() {
        when(docMapper.selectById(DOC_ID)).thenReturn(doc("DELETING"));

        IngestResultVO result = service.apply(USER_ID, DOC_ID, request("READY"));

        assertThat(result.applied()).isFalse();
        assertThat(result.staleVectorCleanupTriggered()).isTrue();
        verify(eventPublisher).publishEvent(any(DocumentVectorCleanupEvent.class));
    }

    @Test
    @DisplayName("同版本重复 READY 回调：幂等忽略，绝不删除当前有效向量")
    void repeatedReadyDoesNotCleanVectors() {
        when(docMapper.selectById(DOC_ID)).thenReturn(doc("READY"));

        IngestResultVO result = service.apply(USER_ID, DOC_ID, request("READY"));

        assertThat(result.applied()).isFalse();
        assertThat(result.staleVectorCleanupTriggered()).isFalse();
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("parent_chunk_id 与 seq 不匹配时拒绝")
    void rejectsForgedParentChunkId() {
        when(docMapper.selectById(DOC_ID)).thenReturn(doc("PARSING"));
        when(courseService.getActiveCourse(USER_ID, COURSE_ID)).thenReturn(course());

        assertThatThrownBy(() -> service.apply(USER_ID, DOC_ID, request("READY", "pc_forged")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
        verify(docMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("X-User-Id 与请求体 user_id 不一致时拒绝")
    void rejectsIdentityMismatch() {
        IngestResultRequest request = new IngestResultRequest(
                "job_1", "10002", String.valueOf(COURSE_ID), String.valueOf(DOC_ID),
                1, 1, "READY", "OPENAI_COMPATIBLE", "BAAI/bge-m3", 1024,
                1, 1, List.of(chunk("pc_50001_v1_1")), null, null);

        assertThatThrownBy(() -> service.apply(USER_ID, DOC_ID, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_FORBIDDEN));
    }

    private static IngestResultRequest request(String status) {
        return request(status, "pc_50001_v1_1");
    }

    private static IngestResultRequest request(String status, String parentId) {
        if ("FAILED".equals(status)) {
            return new IngestResultRequest("job_1", String.valueOf(USER_ID),
                    String.valueOf(COURSE_ID), String.valueOf(DOC_ID), 1, 1,
                    "FAILED", null, null, null, 0, 0, List.of(),
                    "E-RAG-01", "扫描件");
        }
        return new IngestResultRequest("job_1", String.valueOf(USER_ID),
                String.valueOf(COURSE_ID), String.valueOf(DOC_ID), 1, 1,
                "READY", "OPENAI_COMPATIBLE", "BAAI/bge-m3", 1024,
                1, 1, List.of(chunk(parentId)), null, null);
    }

    private static IngestResultRequest.ParentChunkItem chunk(String parentId) {
        return new IngestResultRequest.ParentChunkItem(parentId, 1, 1, 1,
                "第1章", "内容内容", 4);
    }

    private static KnowledgeDocEntity doc(String status) {
        KnowledgeDocEntity doc = new KnowledgeDocEntity();
        doc.setDocId(DOC_ID);
        doc.setUserId(USER_ID);
        doc.setCourseId(COURSE_ID);
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
}
