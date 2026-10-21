package com.scholarclaw.modules.knowledge.service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 共享卷路径与文件操作（{@code 03} §4.3、{@code 04} §4.4.2）。
 *
 * <p>Java 拥有 {@code /data/scholarclaw/storage} 的读写权：上传先写 {@code tmp/}，
 * 再移动到 {@code raw/{user_id}/{course_id}/{doc_id}{ext}}。所有路径都归一化并校验
 * 落在根目录内，文件名不会直接参与路径拼接（只用自增 {@code doc_id} 与白名单扩展名）。
 */
@Service
public class StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageService.class);

    private final Path root;

    public StorageService(@Value("${sc.storage.root:/data/scholarclaw/storage}") String root) {
        this.root = Paths.get(root).toAbsolutePath().normalize();
    }

    public Path createTempFile() throws IOException {
        Path tmpDir = root.resolve("tmp");
        Files.createDirectories(tmpDir);
        return Files.createTempFile(tmpDir, "upload-", ".tmp");
    }

    /** 移动到 raw 目录并返回相对路径（固定 `raw/{uid}/{cid}/{docId}{ext}`）。 */
    public String moveToRaw(Path tempFile, Long userId, Long courseId, Long docId, String extension) {
        Path directory = root.resolve("raw").resolve(String.valueOf(userId))
                .resolve(String.valueOf(courseId)).normalize();
        Path target = directory.resolve(docId + extension).normalize();
        ensureWithinRoot(directory);
        ensureWithinRoot(target);
        try {
            Files.createDirectories(directory);
            try {
                Files.move(tempFile, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return root.relativize(target).toString().replace('\\', '/');
        } catch (IOException e) {
            throw new IllegalStateException("课件文件移动失败", e);
        }
    }

    public String rawRelativePath(Long userId, Long courseId, Long docId, String extension) {
        return "raw/" + userId + "/" + courseId + "/" + docId + extension;
    }

    public void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("清理临时文件失败 path={}", path.getFileName());
        }
    }

    public void deleteRawQuietly(Long userId, Long courseId, Long docId, String extension) {
        deleteQuietly(root.resolve(rawRelativePath(userId, courseId, docId, extension)).normalize());
    }

    private void ensureWithinRoot(Path candidate) {
        if (!candidate.startsWith(root)) {
            throw new IllegalArgumentException("路径越界");
        }
    }
}
