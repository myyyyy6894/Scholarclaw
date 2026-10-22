package com.scholarclaw.modules.knowledge.service;

import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.knowledge.dto.KnowledgeDocVO;
import com.scholarclaw.modules.knowledge.entity.KnowledgeDocEntity;
import com.scholarclaw.modules.knowledge.mq.IngestJobPublisher;
import com.scholarclaw.modules.knowledge.mq.IngestJobType;
import com.scholarclaw.modules.knowledge.mq.IngestMessage;
import com.scholarclaw.modules.knowledge.mq.IngestMessageFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 文档解析任务的编排层（TASK-501）。
 *
 * <p>数据库状态迁移由 {@link KnowledgeDocService} 的短事务完成；本层在事务提交后发布
 * `MQ-INGEST-001`。`INITIAL_INGEST` 发布失败时保留 `UPLOADED` 交给僵尸巡检补投；
 * 重试/重建已在事务中转为 `PARSING`，发布失败会明确报错并记录 job/doc 标识，绝不伪装成功。
 */
@Service
public class KnowledgeIngestService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestService.class);

    private final KnowledgeDocService docService;
    private final IngestJobPublisher publisher;

    public KnowledgeIngestService(KnowledgeDocService docService, IngestJobPublisher publisher) {
        this.docService = docService;
        this.publisher = publisher;
    }

    /** 上传事务提交后调用：发布 INITIAL_INGEST，成功后才推进为 PARSING。 */
    public boolean enqueueInitial(Long userId, Long docId) {
        KnowledgeDocEntity doc = docService.requireOwnedDoc(userId, docId);
        if (!"UPLOADED".equals(doc.getStatus())
                || doc.getStorageRelPath() == null
                || "pending".equals(doc.getStorageRelPath())) {
            return false;
        }
        String jobId = IngestMessageFactory.newJobId();
        boolean published = publish(doc, IngestJobType.INITIAL_INGEST, jobId);
        if (published) {
            docService.markParsing(doc.getDocId(), doc.getParseVersion(), jobId);
        }
        return published;
    }

    public KnowledgeDocVO retry(Long userId, Long docId) {
        String jobId = IngestMessageFactory.newJobId();
        KnowledgeDocEntity doc = docService.retryFailed(userId, docId, jobId);
        requirePublished(doc, IngestJobType.RETRY_FAILED, jobId);
        return docService.toVO(doc);
    }

    public KnowledgeDocVO reindex(Long userId, Long docId) {
        String jobId = IngestMessageFactory.newJobId();
        KnowledgeDocEntity doc = docService.reindexReady(userId, docId, jobId);
        requirePublished(doc, IngestJobType.REINDEX, jobId);
        return docService.toVO(doc);
    }

    /**
     * 课程切换事务提交后，为该课程所有 `PARSING` 文档发布 `REINDEX`。
     *
     * @return 成功入队的文档数
     */
    public int publishReindexForCourse(Long userId, Long courseId, int courseIndexVersion) {
        int published = 0;
        for (KnowledgeDocEntity doc : docService.findParsingForCourse(
                userId, courseId, courseIndexVersion)) {
            String jobId = IngestMessageFactory.newJobId();
            if (publish(doc, IngestJobType.REINDEX, jobId)) {
                docService.recordEnqueued(doc.getDocId(), doc.getParseVersion(), jobId);
                published++;
            }
        }
        return published;
    }

    private void requirePublished(KnowledgeDocEntity doc, IngestJobType type, String jobId) {
        if (!publish(doc, type, jobId)) {
            log.error("文档已进入 PARSING 但任务投递失败 jobId={} docId={} type={}",
                    jobId, doc.getDocId(), type);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "任务已进入解析状态，但消息投递失败；请稍后刷新或联系管理员。");
        }
    }

    private boolean publish(KnowledgeDocEntity doc, IngestJobType type, String jobId) {
        try {
            IngestMessage message = IngestMessageFactory.from(doc, type, jobId);
            boolean ok = publisher.publish(message);
            if (!ok && publisher.isEnabled()) {
                log.error("解析任务未入队 jobId={} docId={} type={}",
                        jobId, doc.getDocId(), type);
            }
            return ok;
        } catch (RuntimeException e) {
            log.error("无法构造解析任务 docId={} type={} error={}",
                    doc.getDocId(), type, e.getClass().getSimpleName());
            return false;
        }
    }
}
