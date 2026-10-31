package com.scholarclaw.modules.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scholarclaw.modules.knowledge.entity.KnowledgeDocEntity;
import com.scholarclaw.modules.knowledge.mapper.KnowledgeParentChunkMapper;
import com.scholarclaw.modules.knowledge.service.KnowledgeVectorCleanupService.VectorCleanupResult;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KnowledgeDeletionServiceTest {

    private static final Long USER_ID = 10001L;
    private static final Long COURSE_ID = 2001L;
    private static final Long DOC_ID = 50001L;

    private KnowledgeVectorCleanupService vectorCleanupService;
    private StorageService storageService;
    private KnowledgeDocService docService;
    private KnowledgeParentChunkMapper parentChunkMapper;
    private KnowledgeDeletionService service;

    @BeforeEach
    void setUp() {
        vectorCleanupService = mock(KnowledgeVectorCleanupService.class);
        storageService = mock(StorageService.class);
        docService = mock(KnowledgeDocService.class);
        parentChunkMapper = mock(KnowledgeParentChunkMapper.class);
        service = new KnowledgeDeletionService(vectorCleanupService, storageService,
                docService, parentChunkMapper);
    }

    @Test
    @DisplayName("单文档：向量与文件都成功后才置 DELETED")
    void finalizesDocumentWhenResourcesDeleted() {
        KnowledgeDocEntity doc = doc(DOC_ID, "DELETING");
        doc.setStorageRelPath("raw/10001/2001/50001.pdf");
        when(vectorCleanupService.cleanupDocument(USER_ID, COURSE_ID, DOC_ID, null))
                .thenReturn(VectorCleanupResult.succeeded());
        when(storageService.deleteRawByRelativePath(doc.getStorageRelPath())).thenReturn(true);
        when(docService.markDeleted(USER_ID, DOC_ID)).thenReturn(true);

        assertThat(service.finalizeDocumentDeletion(USER_ID, doc, null)).isTrue();
        verify(docService).markDeleted(USER_ID, DOC_ID);
    }

    @Test
    @DisplayName("单文档：物理文件删除失败时保留 DELETING")
    void keepsDeletingWhenFileDeleteFails() {
        KnowledgeDocEntity doc = doc(DOC_ID, "DELETING");
        when(vectorCleanupService.cleanupDocument(USER_ID, COURSE_ID, DOC_ID, null))
                .thenReturn(VectorCleanupResult.succeeded());
        when(storageService.deleteRawByRelativePath(doc.getStorageRelPath())).thenReturn(false);

        assertThat(service.finalizeDocumentDeletion(USER_ID, doc, null)).isFalse();
        verify(docService, never()).markDeleted(USER_ID, DOC_ID);
    }

    @Test
    @DisplayName("课程：目录删除成功后只把非 DELETED 文档置终态")
    void finalizesCourseDocs() {
        KnowledgeDocEntity deleting = doc(1L, "DELETING");
        KnowledgeDocEntity deleted = doc(2L, "DELETED");
        when(vectorCleanupService.cleanupCourse(USER_ID, COURSE_ID, null)).thenReturn(VectorCleanupResult.succeeded());
        when(storageService.deleteRawDirectoryForCourse(USER_ID, COURSE_ID)).thenReturn(true);
        when(docService.listByCourse(USER_ID, COURSE_ID)).thenReturn(List.of(deleting, deleted));
        when(docService.markDeleted(USER_ID, 1L)).thenReturn(true);

        assertThat(service.finalizeCourseDeletion(USER_ID, COURSE_ID, null)).isTrue();
        verify(docService).markDeleted(USER_ID, 1L);
        verify(docService, never()).markDeleted(USER_ID, 2L);
    }

    @Test
    @DisplayName("课程：目录清理失败时保留 DELETING 并返回 false")
    void keepsCourseDocsWhenDirectoryFails() {
        when(vectorCleanupService.cleanupCourse(USER_ID, COURSE_ID, null)).thenReturn(VectorCleanupResult.succeeded());
        when(storageService.deleteRawDirectoryForCourse(USER_ID, COURSE_ID)).thenReturn(false);

        assertThat(service.finalizeCourseDeletion(USER_ID, COURSE_ID, null)).isFalse();
        verify(docService, never()).markDeleted(USER_ID, 1L);
    }

    @Test
    @DisplayName("账号：向量与文件都成功后物理删除父块与文档行")
    void finalizesUserRows() {
        when(vectorCleanupService.cleanupUser(USER_ID)).thenReturn(VectorCleanupResult.succeeded());
        when(storageService.deleteRawDirectoryForUser(USER_ID)).thenReturn(true);

        assertThat(service.finalizeUserDeletion(USER_ID)).isTrue();
        verify(parentChunkMapper).deleteByUser(USER_ID);
        verify(docService).deleteAllByUser(USER_ID);
    }

    @Test
    @DisplayName("账号：向量清理失败时保留数据库行等待 GC")
    void keepsUserRowsWhenVectorsFail() {
        when(vectorCleanupService.cleanupUser(USER_ID)).thenReturn(VectorCleanupResult.failed(false));
        when(storageService.deleteRawDirectoryForUser(USER_ID)).thenReturn(true);

        assertThat(service.finalizeUserDeletion(USER_ID)).isFalse();
        verify(parentChunkMapper, never()).deleteByUser(USER_ID);
        verify(docService, never()).deleteAllByUser(USER_ID);
    }

    @Test
    @DisplayName("单文档：向量失败但补偿已落库 → 允许进入 DELETED")
    void finalizesDocumentWhenCompensationPersisted() {
        KnowledgeDocEntity doc = doc(DOC_ID, "DELETING");
        doc.setStorageRelPath("raw/10001/2001/50001.pdf");
        when(vectorCleanupService.cleanupDocument(USER_ID, COURSE_ID, DOC_ID, null))
                .thenReturn(VectorCleanupResult.failed(true));
        when(storageService.deleteRawByRelativePath(doc.getStorageRelPath())).thenReturn(true);
        when(docService.markDeleted(USER_ID, DOC_ID)).thenReturn(true);

        assertThat(service.finalizeDocumentDeletion(USER_ID, doc, null)).isTrue();
        verify(docService).markDeleted(USER_ID, DOC_ID);
    }

    @Test
    @DisplayName("单文档：向量失败且补偿未落库 → 保留 DELETING，绝不提前终态")
    void keepsDeletingWhenVectorsUnresolved() {
        KnowledgeDocEntity doc = doc(DOC_ID, "DELETING");
        doc.setStorageRelPath("raw/10001/2001/50001.pdf");
        when(vectorCleanupService.cleanupDocument(USER_ID, COURSE_ID, DOC_ID, null))
                .thenReturn(VectorCleanupResult.failed(false));
        when(storageService.deleteRawByRelativePath(doc.getStorageRelPath())).thenReturn(true);

        assertThat(service.finalizeDocumentDeletion(USER_ID, doc, null)).isFalse();
        verify(docService, never()).markDeleted(USER_ID, DOC_ID);
    }

    @Test
    @DisplayName("课程：向量失败且补偿未落库 → 不置文档终态，调用方不得删课程行")
    void keepsCourseDocsWhenVectorsUnresolved() {
        when(vectorCleanupService.cleanupCourse(USER_ID, COURSE_ID, null))
                .thenReturn(VectorCleanupResult.failed(false));
        when(storageService.deleteRawDirectoryForCourse(USER_ID, COURSE_ID)).thenReturn(true);

        assertThat(service.finalizeCourseDeletion(USER_ID, COURSE_ID, null)).isFalse();
        verify(docService, never()).markDeleted(USER_ID, 1L);
    }

    private static KnowledgeDocEntity doc(Long docId, String status) {
        KnowledgeDocEntity doc = new KnowledgeDocEntity();
        doc.setDocId(docId);
        doc.setUserId(USER_ID);
        doc.setCourseId(COURSE_ID);
        doc.setStatus(status);
        return doc;
    }
}
