package com.scholarclaw.modules.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scholarclaw.common.enums.IndexStatus;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.course.entity.CourseNamespaceEntity;
import com.scholarclaw.modules.course.service.CourseService;
import com.scholarclaw.modules.knowledge.dto.KnowledgeDocVO;
import com.scholarclaw.modules.knowledge.entity.KnowledgeDocEntity;
import com.scholarclaw.modules.knowledge.service.FileUploadSecurityValidator.ValidatedUpload;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

/** 上传事务边界：行锁、查重结构化详情、落盘失败回滚。 */
class KnowledgeUploadCommitServiceTest {

    private static final long USER_ID = 10001L;
    private static final long COURSE_ID = 2001L;
    private static final long DOC_ID = 50001L;
    private static final Path TEMP = Path.of("/tmp/upload.tmp");
    private static final ValidatedUpload STAGED =
            new ValidatedUpload("a.pdf", ".pdf", 10, "sha", TEMP);

    private KnowledgeDocService docService;
    private CourseService courseService;
    private StorageService storageService;
    private KnowledgeUploadCommitService service;

    @BeforeEach
    void setUp() {
        docService = mock(KnowledgeDocService.class);
        courseService = mock(CourseService.class);
        storageService = mock(StorageService.class);
        service = new KnowledgeUploadCommitService(docService, courseService, storageService);
    }

    @Test
    @DisplayName("事务内先锁课程行；REBUILDING 时拒绝")
    void locksCourseAndRejectsRebuilding() {
        when(courseService.getActiveCourseForUpdate(USER_ID, COURSE_ID))
                .thenReturn(course(IndexStatus.REBUILDING));

        assertThatThrownBy(() -> service.commit(USER_ID, COURSE_ID, STAGED))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.E_SYS_03));
        verify(docService, never()).createDocumentMetadata(any(), any(), any(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("重复 SHA 返回 409 并携带已有 doc_id")
    void duplicateCarriesExistingDocId() {
        when(courseService.getActiveCourseForUpdate(USER_ID, COURSE_ID))
                .thenReturn(course(IndexStatus.ACTIVE));
        when(docService.findActiveShaDoc(USER_ID, COURSE_ID, "sha")).thenReturn(doc());

        assertThatThrownBy(() -> service.commit(USER_ID, COURSE_ID, STAGED))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException business = (BusinessException) ex;
                    assertThat(business.getErrorCode()).isEqualTo(ErrorCode.DOC_DUPLICATE_SHA256);
                    assertThat((Map<String, Object>) business.getData())
                            .containsEntry("existing_doc_id", "50001")
                            .containsEntry("existing_doc_name", "a.pdf");
                });
        verify(docService, never()).createDocumentMetadata(any(), any(), any(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("并发唯一键冲突回查后返回已有 doc_id")
    void duplicateKeyFallsBackToLookup() {
        when(courseService.getActiveCourseForUpdate(USER_ID, COURSE_ID))
                .thenReturn(course(IndexStatus.ACTIVE));
        when(docService.findActiveShaDoc(USER_ID, COURSE_ID, "sha"))
                .thenReturn(null, doc());
        when(docService.createDocumentMetadata(any(), any(), any(), anyLong(), any(), any()))
                .thenThrow(new DuplicateKeyException("uk_doc"));

        assertThatThrownBy(() -> service.commit(USER_ID, COURSE_ID, STAGED))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException business = (BusinessException) ex;
                    assertThat(business.getErrorCode()).isEqualTo(ErrorCode.DOC_DUPLICATE_SHA256);
                    assertThat((Map<String, Object>) business.getData())
                            .containsEntry("existing_doc_id", "50001");
                });
    }

    @Test
    @DisplayName("成功：插入元数据、移动文件并回填路径")
    void movesAndBackfillsPath() {
        when(courseService.getActiveCourseForUpdate(USER_ID, COURSE_ID))
                .thenReturn(course(IndexStatus.ACTIVE));
        when(docService.createDocumentMetadata(any(), any(), any(), anyLong(), any(), any()))
                .thenReturn(doc());
        when(storageService.moveToRaw(TEMP, USER_ID, COURSE_ID, DOC_ID, ".pdf"))
                .thenReturn("raw/10001/2001/50001.pdf");
        when(docService.toVO(any())).thenReturn(sampleVo());

        KnowledgeDocVO result = service.commit(USER_ID, COURSE_ID, STAGED);

        assertThat(result.docId()).isEqualTo(DOC_ID);
        verify(docService).updateStorageRelPath(USER_ID, DOC_ID, "raw/10001/2001/50001.pdf");
        verify(storageService, never()).deleteRawQuietly(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("文件移动失败：事务抛错并尝试删除目标文件")
    void compensatesWhenMoveFails() {
        when(courseService.getActiveCourseForUpdate(USER_ID, COURSE_ID))
                .thenReturn(course(IndexStatus.ACTIVE));
        when(docService.createDocumentMetadata(any(), any(), any(), anyLong(), any(), any()))
                .thenReturn(doc());
        when(storageService.moveToRaw(any(), anyLong(), anyLong(), anyLong(), any()))
                .thenThrow(new IllegalStateException("disk full"));
        when(storageService.deleteRawQuietly(USER_ID, COURSE_ID, DOC_ID, ".pdf")).thenReturn(true);

        assertThatThrownBy(() -> service.commit(USER_ID, COURSE_ID, STAGED))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INTERNAL_ERROR));
        verify(storageService).deleteRawQuietly(USER_ID, COURSE_ID, DOC_ID, ".pdf");
    }

    @Test
    @DisplayName("路径回填失败：同样回滚并清理目标文件")
    void compensatesWhenPathBackfillFails() {
        when(courseService.getActiveCourseForUpdate(USER_ID, COURSE_ID))
                .thenReturn(course(IndexStatus.ACTIVE));
        when(docService.createDocumentMetadata(any(), any(), any(), anyLong(), any(), any()))
                .thenReturn(doc());
        when(storageService.moveToRaw(any(), anyLong(), anyLong(), anyLong(), any()))
                .thenReturn("raw/10001/2001/50001.pdf");
        org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.STATE_CONFLICT))
                .when(docService).updateStorageRelPath(USER_ID, DOC_ID, "raw/10001/2001/50001.pdf");
        when(storageService.deleteRawQuietly(USER_ID, COURSE_ID, DOC_ID, ".pdf")).thenReturn(false);

        assertThatThrownBy(() -> service.commit(USER_ID, COURSE_ID, STAGED))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INTERNAL_ERROR));
        verify(storageService).deleteRawQuietly(USER_ID, COURSE_ID, DOC_ID, ".pdf");
    }

    private static CourseNamespaceEntity course(IndexStatus status) {
        CourseNamespaceEntity course = new CourseNamespaceEntity();
        course.setCourseId(COURSE_ID);
        course.setUserId(USER_ID);
        course.setIndexVersion(1);
        course.setIndexStatus(status.name());
        course.setIsDeleting(0);
        return course;
    }

    private static KnowledgeDocEntity doc() {
        KnowledgeDocEntity doc = new KnowledgeDocEntity();
        doc.setDocId(DOC_ID);
        doc.setUserId(USER_ID);
        doc.setCourseId(COURSE_ID);
        doc.setDocName("a.pdf");
        doc.setFileExt(".pdf");
        doc.setFileSizeBytes(10L);
        doc.setStatus("UPLOADED");
        doc.setParseVersion(1);
        doc.setCourseIndexVersion(1);
        doc.setCreatedAt(LocalDateTime.now());
        doc.setUpdatedAt(LocalDateTime.now());
        return doc;
    }

    private static KnowledgeDocVO sampleVo() {
        return new KnowledgeDocVO(DOC_ID, COURSE_ID, "a.pdf", ".pdf", 10, "UPLOADED",
                1, 1, null, null, 0, 0, 0, null, null, null, null, null,
                "2026-10-21T10:00:00.000+08:00", "2026-10-21T10:00:00.000+08:00");
    }
}
