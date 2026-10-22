package com.scholarclaw.modules.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scholarclaw.modules.knowledge.entity.KnowledgeDocEntity;
import com.scholarclaw.modules.knowledge.mapper.KnowledgeDocMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 上传半成品与孤立文件恢复扫描。 */
class KnowledgeUploadRecoveryServiceTest {

    private static final long USER_ID = 10001L;
    private static final long COURSE_ID = 2001L;
    private static final long DOC_ID = 50001L;

    @TempDir
    Path root;

    private KnowledgeDocMapper docMapper;
    private StorageService storageService;
    private KnowledgeUploadRecoveryService service;

    @BeforeEach
    void setUp() {
        docMapper = mock(KnowledgeDocMapper.class);
        storageService = new StorageService(root.toString());
        service = new KnowledgeUploadRecoveryService(docMapper, storageService, true, 10, 60);
    }

    @Test
    @DisplayName("raw 文件存在时回填 pending 路径，而不是删除元数据")
    void restoresPendingPathWhenRawExists() throws Exception {
        Path raw = root.resolve("raw/10001/2001/50001.pdf");
        Files.createDirectories(raw.getParent());
        Files.write(raw, new byte[]{1});
        when(docMapper.selectList(any())).thenReturn(List.of(pendingDoc()));
        when(docMapper.update(any(), any())).thenReturn(1);

        KnowledgeUploadRecoveryService.RecoveryReport report = service.recover();

        assertThat(report.restoredPaths()).isEqualTo(1);
        assertThat(report.removedMetadata()).isZero();
        verify(docMapper, never()).delete(any());
    }

    @Test
    @DisplayName("raw 文件缺失时删除不可恢复的 pending 元数据")
    void removesPendingWhenRawMissing() {
        when(docMapper.selectList(any())).thenReturn(List.of(pendingDoc()));
        when(docMapper.delete(any())).thenReturn(1);

        KnowledgeUploadRecoveryService.RecoveryReport report = service.recover();

        assertThat(report.removedMetadata()).isEqualTo(1);
        assertThat(report.restoredPaths()).isZero();
    }

    @Test
    @DisplayName("超时临时文件与无法对应文档的 raw 文件被清理")
    void removesStaleTempAndOrphanRaw() throws Exception {
        Path tmp = root.resolve("tmp/upload-old.tmp");
        Files.createDirectories(tmp.getParent());
        Files.write(tmp, new byte[]{1});
        Files.setLastModifiedTime(tmp, FileTime.from(Instant.now().minusSeconds(7200)));

        Path orphan = root.resolve("raw/10001/2001/99999.pdf");
        Files.createDirectories(orphan.getParent());
        Files.write(orphan, new byte[]{1});
        Files.setLastModifiedTime(orphan, FileTime.from(Instant.now().minusSeconds(7200)));

        when(docMapper.selectList(any())).thenReturn(List.of());
        when(docMapper.selectCount(any())).thenReturn(0L);

        KnowledgeUploadRecoveryService.RecoveryReport report = service.recover();

        assertThat(report.removedTempFiles()).isEqualTo(1);
        assertThat(report.removedOrphanRawFiles()).isEqualTo(1);
        assertThat(Files.exists(tmp)).isFalse();
        assertThat(Files.exists(orphan)).isFalse();
    }

    @Test
    @DisplayName("活跃文档对应的 raw 文件不会被误删")
    void keepsRawFileOfLiveDoc() throws Exception {
        Path raw = root.resolve("raw/10001/2001/50001.pdf");
        Files.createDirectories(raw.getParent());
        Files.write(raw, new byte[]{1});
        Files.setLastModifiedTime(raw, FileTime.from(Instant.now().minusSeconds(7200)));

        when(docMapper.selectList(any())).thenReturn(List.of());
        when(docMapper.selectCount(any())).thenReturn(1L);

        KnowledgeUploadRecoveryService.RecoveryReport report = service.recover();

        assertThat(report.removedOrphanRawFiles()).isZero();
        assertThat(Files.exists(raw)).isTrue();
    }

    private static KnowledgeDocEntity pendingDoc() {
        KnowledgeDocEntity doc = new KnowledgeDocEntity();
        doc.setDocId(DOC_ID);
        doc.setUserId(USER_ID);
        doc.setCourseId(COURSE_ID);
        doc.setFileExt(".pdf");
        doc.setStatus("UPLOADED");
        doc.setStorageRelPath("pending");
        doc.setUpdatedAt(LocalDateTime.now().minusHours(1));
        return doc;
    }
}
