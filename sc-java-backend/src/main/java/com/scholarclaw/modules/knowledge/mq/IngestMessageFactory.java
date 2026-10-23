package com.scholarclaw.modules.knowledge.mq;

import com.scholarclaw.common.config.ScTimeZone;
import com.scholarclaw.common.filter.TraceContext;
import com.scholarclaw.modules.knowledge.entity.KnowledgeDocEntity;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/** 构造 `MQ-INGEST-001` 消息；不读取任何密钥，也不访问数据库。 */
public final class IngestMessageFactory {

    private static final DateTimeFormatter JOB_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmssSSS", Locale.ROOT);
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    private IngestMessageFactory() {
    }

    /** 生成全局唯一任务 ID：`job_{yyyyMMdd_HHmmssSSS}_{6hex}`。 */
    public static String newJobId() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        return "job_" + ScTimeZone.now().format(JOB_TIME) + "_" + suffix;
    }

    /**
     * @param doc 必须已完成落盘（`storage_rel_path` 不得为 `pending`）
     * @throws IllegalStateException 文档尚未落盘、缺少 SHA-256 等不可入队状态
     */
    public static IngestMessage from(KnowledgeDocEntity doc, IngestJobType type, String jobId) {
        return from(doc, type, jobId,
                doc.getParseVersion() == null ? 1 : doc.getParseVersion(),
                doc.getRetryCount() == null ? 0 : doc.getRetryCount());
    }

    /** 巡检补投使用：消息里的版本/重试次数以原子认领后的新值为准。 */
    public static IngestMessage from(KnowledgeDocEntity doc, IngestJobType type, String jobId,
                                     int parseVersion, int retryCount) {
        if (doc == null || doc.getDocId() == null || doc.getUserId() == null
                || doc.getCourseId() == null) {
            throw new IllegalStateException("文档身份信息不完整，不能构造解析任务");
        }
        String storagePath = doc.getStorageRelPath();
        if (storagePath == null || storagePath.isBlank() || "pending".equals(storagePath)) {
            throw new IllegalStateException("文档尚未完成落盘，不能入队");
        }
        if (doc.getFileSha256() == null || doc.getFileSha256().isBlank()) {
            throw new IllegalStateException("文档缺少 SHA-256，不能入队");
        }
        return new IngestMessage(
                jobId,
                type.name(),
                TraceContext.currentOrGeneratedTraceId(),
                String.valueOf(doc.getUserId()),
                String.valueOf(doc.getCourseId()),
                doc.getCourseIndexVersion() == null ? 1 : doc.getCourseIndexVersion(),
                String.valueOf(doc.getDocId()),
                parseVersion,
                doc.getDocName(),
                storagePath,
                doc.getFileSha256(),
                retryCount,
                ScTimeZone.now().format(TIMESTAMP));
    }
}
