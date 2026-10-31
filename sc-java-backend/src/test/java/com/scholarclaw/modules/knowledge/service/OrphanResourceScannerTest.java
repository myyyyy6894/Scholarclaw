package com.scholarclaw.modules.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.scholarclaw.modules.knowledge.entity.KnowledgeDocEntity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OrphanResourceScannerTest {

    private static final Long USER_ID = 10001L;
    private static final Long COURSE_ID = 2001L;

    @TempDir
    Path root;

    private KnowledgeDocService docService;
    private OrphanResourceScanner scanner;

    @BeforeEach
    void setUp() {
        docService = mock(KnowledgeDocService.class);
        scanner = new OrphanResourceScanner(new StorageService(root.toString()), docService);
    }

    @Test
    @DisplayName("删除 DELETED 文档与无文档行的旧文件，保留有效文档与新鲜文件")
    void deletesOnlyStaleOrphans() throws IOException {
        Path deletedDocFile = file(501L, "pdf", Duration.ofHours(2));
        Path readyDocFile = file(502L, "pdf", Duration.ofHours(2));
        Path noDocFile = file(503L, "md", Duration.ofHours(2));
        Path freshFile = file(504L, "pdf", Duration.ofMinutes(1));
        Path invalidName = file("not-a-doc", "pdf", Duration.ofHours(2));

        when(docService.findOwnedDoc(USER_ID, 501L)).thenReturn(doc(501L, "DELETED"));
        when(docService.findOwnedDoc(USER_ID, 502L)).thenReturn(doc(502L, "READY"));
        when(docService.findOwnedDoc(USER_ID, 503L)).thenReturn(null);
        when(docService.findOwnedDoc(USER_ID, 504L)).thenReturn(null);

        OrphanResourceScanner.OrphanScanResult result =
                scanner.scanOrphanRawFiles(Duration.ofMinutes(60), 100);

        assertThat(result.scanned()).isEqualTo(3);
        assertThat(result.deleted()).isEqualTo(2);
        assertThat(result.failed()).isZero();
        assertThat(Files.exists(deletedDocFile)).isFalse();
        assertThat(Files.exists(readyDocFile)).isTrue();
        assertThat(Files.exists(noDocFile)).isFalse();
        assertThat(Files.exists(freshFile)).isTrue();
        assertThat(Files.exists(invalidName)).isTrue();
    }

    @Test
    @DisplayName("DELETING 文档保留给删除补偿处理，不当作孤儿")
    void keepsDeletingDocuments() throws IOException {
        Path deleting = file(601L, "pdf", Duration.ofHours(2));
        when(docService.findOwnedDoc(USER_ID, 601L)).thenReturn(doc(601L, "DELETING"));

        OrphanResourceScanner.OrphanScanResult result =
                scanner.scanOrphanRawFiles(Duration.ofMinutes(60), 100);

        assertThat(result.deleted()).isZero();
        assertThat(Files.exists(deleting)).isTrue();
    }

    private Path file(Long docId, String extension, Duration age) throws IOException {
        return file(String.valueOf(docId), extension, age);
    }

    private Path file(String name, String extension, Duration age) throws IOException {
        Path dir = root.resolve("raw").resolve(String.valueOf(USER_ID))
                .resolve(String.valueOf(COURSE_ID));
        Files.createDirectories(dir);
        Path file = dir.resolve(name + "." + extension);
        Files.writeString(file, "x");
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(age)));
        return file;
    }

    private static KnowledgeDocEntity doc(Long docId, String status) {
        KnowledgeDocEntity doc = new KnowledgeDocEntity();
        doc.setDocId(docId);
        doc.setUserId(USER_ID);
        doc.setStatus(status);
        return doc;
    }
}
