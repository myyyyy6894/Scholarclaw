package com.scholarclaw.modules.knowledge.service;

import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.course.service.CourseService;
import com.scholarclaw.modules.knowledge.dto.KnowledgeDocVO;
import com.scholarclaw.modules.knowledge.entity.KnowledgeDocEntity;
import com.scholarclaw.modules.knowledge.mq.IngestJobPublisher;
import com.scholarclaw.modules.knowledge.mq.IngestJobType;
import com.scholarclaw.modules.knowledge.mq.IngestMessage;
import com.scholarclaw.modules.knowledge.mq.IngestMessageFactory;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 文档解析任务的编排层（TASK-501）。
 *
 * <p>数据库状态迁移由 {@link KnowledgeDocService} 的短事务完成；本层在事务提交后发布
 * `MQ-INGEST-001`。`INITIAL_INGEST` 先原子认领为 `PARSING`，broker 确认后才回填
 * `enqueued_at`；未确认时保留可恢复状态交给僵尸巡检补投。
 * 重试/重建发布失败会明确报错并记录 job/doc 标识，绝不伪装成功。
 */
@Service
public class KnowledgeIngestService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestService.class);

    private final KnowledgeDocService docService;
    private final IngestJobPublisher publisher;
    private final CourseService courseService;

    public KnowledgeIngestService(KnowledgeDocService docService, IngestJobPublisher publisher,
                                  CourseService courseService) {
        this.docService = docService;
        this.publisher = publisher;
        this.courseService = courseService;
    }

    /**
     * 上传事务提交后调用：先原子认领为 `PARSING`，broker 确认后才回填 `enqueued_at`。
     *
     * <p>先推进状态再发布，杜绝“消费者已收到消息但库里仍是 UPLOADED”导致回调被拒的窗口；
     * 发布失败时文档保持 `PARSING + enqueued_at=NULL`，由僵尸巡检按同一任务重投。
     */
    public boolean enqueueInitial(Long userId, Long docId) {
        KnowledgeDocEntity doc = docService.requireOwnedDoc(userId, docId);
        if (!"UPLOADED".equals(doc.getStatus())
                || doc.getStorageRelPath() == null
                || "pending".equals(doc.getStorageRelPath())) {
            return false;
        }
        String jobId = IngestMessageFactory.newJobId();
        if (!docService.claimParsing(docId, doc.getParseVersion(), jobId)) {
            return false;
        }
        boolean published = publish(doc, IngestJobType.INITIAL_INGEST, jobId);
        if (published) {
            docService.recordEnqueued(docId, doc.getParseVersion(), jobId);
        } else {
            log.warn("初始任务未获 broker 确认，保留 PARSING 等待巡检补投 docId={} jobId={}",
                    docId, jobId);
        }
        return published;
    }

    /** 僵尸巡检补投：按当前状态选择 `INITIAL_INGEST`（未入队）或 `RETRY_FAILED`。 */
    public boolean republishStale(KnowledgeDocEntity doc, LocalDateTime staleBefore) {
        boolean initial = "UPLOADED".equals(doc.getStatus());
        IngestJobType type = initial ? IngestJobType.INITIAL_INGEST : IngestJobType.RETRY_FAILED;
        String jobId = IngestMessageFactory.newJobId();
        int oldVersion = doc.getParseVersion() == null ? 1 : doc.getParseVersion();
        if (!docService.claimStaleForRetry(doc.getDocId(), oldVersion, jobId, staleBefore)) {
            return false;
        }
        int newVersion = oldVersion + 1;
        int newRetryCount = (doc.getRetryCount() == null ? 0 : doc.getRetryCount()) + 1;
        boolean published = publish(doc, type, jobId, newVersion, newRetryCount);
        if (published) {
            docService.recordEnqueued(doc.getDocId(), newVersion, jobId);
        } else {
            log.warn("巡检补投未获 broker 确认 docId={} jobId={} retry={}",
                    doc.getDocId(), jobId, newRetryCount);
        }
        return published;
    }

    public KnowledgeDocVO retry(Long userId, Long docId) {
        String jobId = IngestMessageFactory.newJobId();
        KnowledgeDocEntity doc = docService.retryFailed(userId, docId, jobId);
        courseService.markRebuildingIfFailed(userId, doc.getCourseId());
        requirePublished(doc, IngestJobType.RETRY_FAILED, jobId);
        return docService.toVO(doc);
    }

    public KnowledgeDocVO reindex(Long userId, Long docId) {
        String jobId = IngestMessageFactory.newJobId();
        KnowledgeDocEntity doc = docService.reindexReady(userId, docId, jobId);
        courseService.markRebuildingIfFailed(userId, doc.getCourseId());
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
            log.error("文档已进入 PARSING 但任务未获 broker 确认 jobId={} docId={} type={}",
                    jobId, doc.getDocId(), type);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "任务已受理但消息暂未投递，系统将自动补投；请稍后刷新。");
        }
        docService.recordEnqueued(doc.getDocId(), doc.getParseVersion(), jobId);
    }

    private boolean publish(KnowledgeDocEntity doc, IngestJobType type, String jobId) {
        return publish(doc, type, jobId, doc.getParseVersion(),
                doc.getRetryCount() == null ? 0 : doc.getRetryCount());
    }

    private boolean publish(KnowledgeDocEntity doc, IngestJobType type, String jobId,
                            int parseVersion, int retryCount) {
        try {
            IngestMessage message = IngestMessageFactory.from(doc, type, jobId,
                    parseVersion, retryCount);
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
