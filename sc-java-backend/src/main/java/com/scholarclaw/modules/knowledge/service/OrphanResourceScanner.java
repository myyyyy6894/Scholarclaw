package com.scholarclaw.modules.knowledge.service;

import com.scholarclaw.modules.knowledge.entity.KnowledgeDocEntity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 共享卷 raw 孤儿文件扫描（{@code 06} TASK-902）。
 *
 * <p>只删除“数据库已无有效文档行”且文件修改时间早于阈值的文件：
 * <ul>
 *   <li>路径必须符合 `raw/{user_id}/{course_id}/{doc_id}{ext}`，文件名不是数字开头的文件不处理；</li>
 *   <li>文档行不存在或状态为 `DELETED` 才视为孤儿；`DELETING` 仍交给删除补偿处理；</li>
 *   <li>阈值防止误删刚写入、事务尚未提交的上传文件。</li>
 * </ul>
 * 删除失败只记录 ERROR 并计入失败数，不中断其他文件。
 */
@Service
public class OrphanResourceScanner {

    private static final Logger log = LoggerFactory.getLogger(OrphanResourceScanner.class);
    private static final Pattern RAW_FILE = Pattern.compile("raw/(\\d+)/(\\d+)/(\\d+)(\\..+)");

    private final StorageService storageService;
    private final KnowledgeDocService docService;

    public OrphanResourceScanner(StorageService storageService,
                                 KnowledgeDocService docService) {
        this.storageService = storageService;
        this.docService = docService;
    }

    public OrphanScanResult scanOrphanRawFiles(Duration olderThan, int limit) {
        int scanned = 0;
        int deleted = 0;
        int failed = 0;
        Instant threshold = Instant.now().minus(olderThan);
        List<Path> files;
        try {
            files = storageService.listRawFiles();
        } catch (IOException e) {
            log.error("孤儿文件扫描失败：无法遍历 raw 目录 error={}",
                    e.getClass().getSimpleName());
            return new OrphanScanResult(0, 0, 1);
        }
        for (Path file : files) {
            if (scanned >= Math.max(1, limit)) {
                break;
            }
            String relative = storageService.root().relativize(file).toString()
                    .replace('\\', '/');
            Matcher matcher = RAW_FILE.matcher(relative);
            if (!matcher.matches()) {
                continue;
            }
            try {
                FileTime modified = Files.getLastModifiedTime(file);
                if (modified.toInstant().isAfter(threshold)) {
                    continue;
                }
            } catch (IOException e) {
                failed++;
                continue;
            }
            scanned++;
            Long userId = Long.valueOf(matcher.group(1));
            Long docId = Long.valueOf(matcher.group(3));
            KnowledgeDocEntity doc = docService.findOwnedDoc(userId, docId);
            boolean orphan = doc == null
                    || "DELETED".equals(doc.getStatus());
            if (!orphan) {
                continue;
            }
            if (storageService.deleteRawByRelativePath(relative)) {
                deleted++;
                log.info("孤儿 raw 文件已删除 userId={} docId={} path={}",
                        userId, docId, relative);
            } else {
                failed++;
            }
        }
        return new OrphanScanResult(scanned, deleted, failed);
    }

    /** 扫描统计：检查数、删除数、失败数。 */
    public record OrphanScanResult(int scanned, int deleted, int failed) {
    }
}
