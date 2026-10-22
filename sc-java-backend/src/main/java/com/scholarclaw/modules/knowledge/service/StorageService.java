package com.scholarclaw.modules.knowledge.service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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

    /** 共享卷根目录（恢复扫描使用）。 */
    public Path root() {
        return root;
    }

    /** 固定 raw 文件路径；只接受白名单扩展名，调用方需自行保证。 */
    public Path rawFile(Long userId, Long courseId, Long docId, String extension) {
        Path path = root.resolve(rawRelativePath(userId, courseId, docId, extension)).normalize();
        ensureWithinRoot(path);
        return path;
    }

    /** 是否存在指定的 raw 文件。 */
    public boolean rawFileExists(Long userId, Long courseId, Long docId, String extension) {
        return Files.isRegularFile(rawFile(userId, courseId, docId, extension));
    }

    /** 列出 `tmp/` 下早于阈值的普通文件（文件系统时间，不访问数据库）。 */
    public List<Path> listStaleTempFiles(Duration olderThan) throws IOException {
        Path tmpDir = root.resolve("tmp");
        if (!Files.isDirectory(tmpDir)) {
            return List.of();
        }
        Instant threshold = Instant.now().minus(olderThan);
        try (var stream = Files.list(tmpDir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        try {
                            return Files.getLastModifiedTime(path).toInstant().isBefore(threshold);
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .toList();
        }
    }

    /** 列出 raw 下所有普通文件（用于孤儿扫描，调用方按 `raw/{uid}/{cid}/{docId}{ext}` 解析）。 */
    public List<Path> listRawFiles() throws IOException {
        Path rawDir = root.resolve("raw");
        if (!Files.isDirectory(rawDir)) {
            return List.of();
        }
        try (var stream = Files.walk(rawDir)) {
            return stream.filter(Files::isRegularFile).toList();
        }
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

    /** @return true 表示目标不存在或已删除；false 表示删除失败，调用方必须记录可追踪状态。 */
    public boolean deleteRawQuietly(Long userId, Long courseId, Long docId, String extension) {
        Path path = rawFile(userId, courseId, docId, extension);
        try {
            Files.deleteIfExists(path);
            return true;
        } catch (IOException e) {
            log.error("清理 raw 文件失败 path={} error={}", path, e.getClass().getSimpleName());
            return false;
        }
    }

    private void ensureWithinRoot(Path candidate) {
        if (!candidate.startsWith(root)) {
            throw new IllegalArgumentException("路径越界");
        }
    }
}
