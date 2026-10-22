package com.scholarclaw.modules.knowledge.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.scholarclaw.common.config.ScTimeZone;
import com.scholarclaw.common.enums.DocumentStatus;
import com.scholarclaw.modules.knowledge.entity.KnowledgeDocEntity;
import com.scholarclaw.modules.knowledge.mapper.KnowledgeDocMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 上传半成品与孤立文件恢复扫描（{@code 06} TASK-403 补偿、TASK-901 前置兜底）。
 *
 * <p>处理三类残留，全部使用 `knowledge` 模块自己的 Mapper:
 * <ul>
 *   <li>`UPLOADED + storage_rel_path = 'pending'` 且超过阈值的元数据：raw 文件存在则回填
 *       真实路径（可恢复），不存在则删除元数据（不可恢复）；</li>
 *   <li>`tmp/` 下超过阈值的临时文件：直接删除；</li>
 *   <li>raw 下无法对应任何文档行、且已超过阈值的孤立文件：删除。</li>
 * </ul>
 *
 * <p>合法等待 TASK-501 入队的 `UPLOADED` 文档路径已回填，不会被本扫描改动。
 */
@Service
public class KnowledgeUploadRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeUploadRecoveryService.class);

    private final KnowledgeDocMapper docMapper;
    private final StorageService storageService;
    private final boolean enabled;
    private final Duration staleThreshold;
    private final Duration orphanThreshold;

    public KnowledgeUploadRecoveryService(
            KnowledgeDocMapper docMapper,
            StorageService storageService,
            @Value("${sc.knowledge.upload-recovery.enabled:true}") boolean enabled,
            @Value("${sc.knowledge.upload-recovery.stale-minutes:10}") long staleMinutes,
            @Value("${sc.knowledge.upload-recovery.tmp-orphan-minutes:60}") long tmpOrphanMinutes) {
        this.docMapper = docMapper;
        this.storageService = storageService;
        this.enabled = enabled;
        this.staleThreshold = Duration.ofMinutes(staleMinutes);
        this.orphanThreshold = Duration.ofMinutes(tmpOrphanMinutes);
    }

    @Scheduled(initialDelayString = "${sc.knowledge.upload-recovery.initial-delay-ms:300000}",
            fixedDelayString = "${sc.knowledge.upload-recovery.interval-ms:900000}")
    public void scheduledRecover() {
        if (!enabled) {
            return;
        }
        try {
            RecoveryReport report = recover();
            if (report.changed()) {
                log.info("上传恢复扫描完成 {}", report);
            }
        } catch (RuntimeException e) {
            log.error("上传恢复扫描失败 error={}", e.getClass().getSimpleName(), e);
        }
    }

    /** 执行一次恢复扫描；返回计数用于测试与日志。 */
    public RecoveryReport recover() {
        LocalDateTime pendingBefore = ScTimeZone.now().toLocalDateTime()
                .minus(staleThreshold);
        int restored = 0;
        int removedMetadata = 0;
        for (KnowledgeDocEntity doc : docMapper.selectList(Wrappers.<KnowledgeDocEntity>lambdaQuery()
                .eq(KnowledgeDocEntity::getStatus, DocumentStatus.UPLOADED.name())
                .eq(KnowledgeDocEntity::getStorageRelPath, "pending")
                .lt(KnowledgeDocEntity::getUpdatedAt, pendingBefore))) {
            String expected = storageService.rawRelativePath(
                    doc.getUserId(), doc.getCourseId(), doc.getDocId(), doc.getFileExt());
            if (storageService.rawFileExists(doc.getUserId(), doc.getCourseId(),
                    doc.getDocId(), doc.getFileExt())) {
                int affected = docMapper.update(null, Wrappers.<KnowledgeDocEntity>lambdaUpdate()
                        .eq(KnowledgeDocEntity::getDocId, doc.getDocId())
                        .eq(KnowledgeDocEntity::getUserId, doc.getUserId())
                        .eq(KnowledgeDocEntity::getStatus, DocumentStatus.UPLOADED.name())
                        .eq(KnowledgeDocEntity::getStorageRelPath, "pending")
                        .set(KnowledgeDocEntity::getStorageRelPath, expected)
                        .set(KnowledgeDocEntity::getUpdatedAt, ScTimeZone.now().toLocalDateTime()));
                restored += affected;
            } else {
                int affected = docMapper.delete(Wrappers.<KnowledgeDocEntity>lambdaQuery()
                        .eq(KnowledgeDocEntity::getDocId, doc.getDocId())
                        .eq(KnowledgeDocEntity::getUserId, doc.getUserId())
                        .eq(KnowledgeDocEntity::getStatus, DocumentStatus.UPLOADED.name())
                        .eq(KnowledgeDocEntity::getStorageRelPath, "pending"));
                removedMetadata += affected;
            }
        }

        int removedTemp = 0;
        try {
            for (Path temp : storageService.listStaleTempFiles(staleThreshold)) {
                storageService.deleteQuietly(temp);
                removedTemp++;
            }
        } catch (IOException e) {
            log.warn("扫描临时目录失败 error={}", e.getClass().getSimpleName());
        }

        int removedOrphanRaw = 0;
        try {
            long thresholdMillis = System.currentTimeMillis() - orphanThreshold.toMillis();
            for (Path rawFile : storageService.listRawFiles()) {
                if (!isOlderThan(rawFile, thresholdMillis)) {
                    continue;
                }
                Long[] ids = parseRawPath(rawFile);
                if (ids == null) {
                    continue;
                }
                boolean exists = docMapper.selectCount(Wrappers.<KnowledgeDocEntity>lambdaQuery()
                        .eq(KnowledgeDocEntity::getDocId, ids[2])
                        .eq(KnowledgeDocEntity::getUserId, ids[0])
                        .eq(KnowledgeDocEntity::getCourseId, ids[1])) > 0;
                if (!exists) {
                    try {
                        Files.deleteIfExists(rawFile);
                        removedOrphanRaw++;
                    } catch (IOException e) {
                        log.error("删除孤立 raw 文件失败 file={}", rawFile.getFileName());
                    }
                }
            }
        } catch (IOException e) {
            log.warn("扫描 raw 目录失败 error={}", e.getClass().getSimpleName());
        }
        return new RecoveryReport(restored, removedMetadata, removedTemp, removedOrphanRaw);
    }

    /** 解析 `raw/{uid}/{cid}/{docId}{ext}`；格式不符返回 null（不猜测、不删除）。 */
    private Long[] parseRawPath(Path rawFile) {
        Path relative = storageService.root().relativize(rawFile);
        List<String> parts = java.util.Arrays.stream(relative.toString().split("[/\\\\]")).toList();
        if (parts.size() != 4 || !"raw".equals(parts.get(0))) {
            return null;
        }
        String fileName = parts.get(3);
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0) {
            return null;
        }
        try {
            return new Long[]{
                    Long.parseLong(parts.get(1)),
                    Long.parseLong(parts.get(2)),
                    Long.parseLong(fileName.substring(0, dot))};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isOlderThan(Path path, long thresholdMillis) {
        try {
            return Files.getLastModifiedTime(path).toMillis() < thresholdMillis;
        } catch (IOException e) {
            return false;
        }
    }

    /** 一次扫描的计数。 */
    public record RecoveryReport(int restoredPaths, int removedMetadata,
                                 int removedTempFiles, int removedOrphanRawFiles) {
        public boolean changed() {
            return restoredPaths + removedMetadata + removedTempFiles + removedOrphanRawFiles > 0;
        }
    }
}
