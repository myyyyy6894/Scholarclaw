package com.scholarclaw.modules.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.course.service.CourseService;
import com.scholarclaw.modules.knowledge.entity.KnowledgeDocEntity;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KnowledgeFileServiceTest {
    @TempDir Path root;

    @Test
    void returnsOnlyUnremovedOriginalFromItsOwningCourse() throws Exception {
        var courses = mock(CourseService.class);
        var documents = mock(KnowledgeDocService.class);
        var storage = new StorageService(root.toString());
        var files = new KnowledgeFileService(courses, documents, storage);
        var document = new KnowledgeDocEntity();
        document.setCourseId(20L);
        document.setDocName("第一讲.pptx");
        document.setFileExt(".pptx");
        document.setStatus("STORED");
        document.setStorageRelPath("raw/10/20/30.pptx");
        var path = storage.rawFile(10L, 20L, 30L, ".pptx");
        Files.createDirectories(path.getParent());
        byte[] content = {1, 2, 3};
        Files.write(path, content);
        when(documents.requireOwnedDoc(10L, 30L)).thenReturn(document);
        when(documents.requireOwnedDoc(99L, 30L)).thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        var file = files.read(10, 20, 30);
        assertThat(file.filename()).isEqualTo("第一讲.pptx");
        assertThat(file.resource().getContentAsByteArray()).isEqualTo(content);
        assertMissing(() -> files.read(99, 20, 30));
        assertMissing(() -> files.read(10, 21, 30));
        document.setStatus("DELETING");
        assertMissing(() -> files.read(10, 20, 30));
        document.setStatus("READY");
        document.setStorageRelPath("raw/10/20/31.pptx");
        assertMissing(() -> files.read(10, 20, 30));
        document.setStorageRelPath("raw/10/20/30.pptx");
        Files.delete(path);
        assertMissing(() -> files.read(10, 20, 30));
    }

    private static void assertMissing(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }
}
