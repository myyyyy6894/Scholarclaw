package com.scholarclaw.modules.knowledge.service;

import com.scholarclaw.common.event.DocumentVectorCleanupEvent;
import com.scholarclaw.modules.course.service.CourseService;
import com.scholarclaw.modules.knowledge.entity.KnowledgeDocEntity;
import com.scholarclaw.modules.knowledge.mapper.KnowledgeParentChunkMapper;
import com.scholarclaw.modules.notification.service.NotificationBizType;
import com.scholarclaw.modules.notification.service.NotificationCreateCommand;
import com.scholarclaw.modules.notification.service.NotificationService;
import java.util.regex.Pattern;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文档失败态的统一收敛（TASK-506 复核修复）。
 *
 * <p>凡是把文档置为 `FAILED` 的路径（回调、DLQ、解析上下文配置缺失、巡检耗尽）都必须
 * 走这里，保证：条件置失败 → 清空该文档父块 → 事务提交后清理对应版本向量 → 刷新课程状态。
 * 只更新状态而不清残余切片会让旧父块/旧向量在后续误用与泄漏。
 */
@Service
public class KnowledgeFailureService {

    private static final Pattern SAFE_ERROR_CODE = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final KnowledgeDocService docService;
    private final KnowledgeParentChunkMapper parentChunkMapper;
    private final CourseService courseService;
    private final ApplicationEventPublisher eventPublisher;
    private final NotificationService notificationService;

    public KnowledgeFailureService(KnowledgeDocService docService,
                                   KnowledgeParentChunkMapper parentChunkMapper,
                                   CourseService courseService,
                                   ApplicationEventPublisher eventPublisher,
                                   NotificationService notificationService) {
        this.docService = docService;
        this.parentChunkMapper = parentChunkMapper;
        this.courseService = courseService;
        this.eventPublisher = eventPublisher;
        this.notificationService = notificationService;
    }

    /**
     * @return 失败收敛结果；`applied=false` 时会在同一事务内用当前读判断并清理迟到版本向量
     */
    @Transactional(rollbackFor = Exception.class)
    public FailureOutcome fail(Long userId, Long courseId, Long docId, int parseVersion,
                               int courseIndexVersion, String errorCode, String errorMsg) {
        boolean applied = docService.markFailed(docId, parseVersion, errorCode, errorMsg);
        if (!applied) {
            return inspectStale(userId, docId, parseVersion, courseIndexVersion);
        }
        parentChunkMapper.deleteByDoc(userId, docId);
        eventPublisher.publishEvent(
                new DocumentVectorCleanupEvent(userId, courseId, docId, parseVersion));
        courseService.refreshIndexStatus(userId, courseId, courseIndexVersion);
        createFailedNotification(userId, docId, errorCode);
        return new FailureOutcome(true, false, "FAILED");
    }

    /**
     * 解析失败站内通知（TASK-802）。
     *
     * <p>与失败状态在同一事务；通知写入失败会回滚 {@code FAILED}，让回调/巡检重试。
     * 只暴露稳定的错误码，不回传 Python 原始错误信息，避免内部细节进入用户通知。
     */
    private void createFailedNotification(Long userId, Long docId, String rawErrorCode) {
        KnowledgeDocEntity doc = docService.findOwnedDoc(userId, docId);
        if (doc == null) {
            return;
        }
        String errorCode = rawErrorCode != null && SAFE_ERROR_CODE.matcher(rawErrorCode).matches()
                ? rawErrorCode : "UNKNOWN_ERROR";
        String docName = doc.getDocName() == null ? "未命名课件" : doc.getDocName();
        notificationService.createInTx(new NotificationCreateCommand(
                userId,
                NotificationBizType.DOC_INGEST_FAILED,
                docId,
                "❌ 课件《" + docName + "》解析入库失败",
                "课件解析失败（错误码：" + errorCode + "），请在文档列表中重试或更换文件。",
                null));
    }

    /**
     * 乐观锁/失败收敛未生效时，用当前读（`FOR UPDATE`）重新判断是否仍需清理迟到向量。
     *
     * <p>REPEATABLE-READ 下普通查询会返回事务快照，看不到并发已提交的新版本；
     * 只有锁定读才能正确判断。调用方必须已开启事务。
     */
    public FailureOutcome inspectStale(Long userId, Long docId, int requestParseVersion,
                                       int requestCourseIndexVersion) {
        KnowledgeDocEntity current = docService.findOwnedDocForUpdate(userId, docId);
        if (current == null) {
            return new FailureOutcome(false, false, null);
        }
        boolean cleanup = shouldCleanup(current, requestParseVersion, requestCourseIndexVersion);
        if (cleanup) {
            eventPublisher.publishEvent(new DocumentVectorCleanupEvent(
                    userId, current.getCourseId(), docId, requestParseVersion));
        }
        return new FailureOutcome(false, cleanup, current.getStatus());
    }

    private static boolean shouldCleanup(KnowledgeDocEntity current, int requestParseVersion,
                                         int requestCourseIndexVersion) {
        boolean deleting = "DELETING".equals(current.getStatus())
                || "DELETED".equals(current.getStatus());
        boolean staleVersion = requestParseVersion < current.getParseVersion();
        boolean courseVersionChanged = !current.getCourseIndexVersion()
                .equals(requestCourseIndexVersion);
        return deleting || staleVersion || courseVersionChanged;
    }

    /** 失败收敛结果：是否落库、是否登记了迟到向量清理、当前权威状态。 */
    public record FailureOutcome(boolean applied, boolean staleCleanupTriggered,
                                 String currentStatus) {
    }
}
