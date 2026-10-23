package com.scholarclaw.modules.knowledge.service;

import com.scholarclaw.common.event.DocumentVectorCleanupEvent;
import com.scholarclaw.modules.course.service.CourseService;
import com.scholarclaw.modules.knowledge.entity.KnowledgeDocEntity;
import com.scholarclaw.modules.knowledge.mapper.KnowledgeParentChunkMapper;
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

    private final KnowledgeDocService docService;
    private final KnowledgeParentChunkMapper parentChunkMapper;
    private final CourseService courseService;
    private final ApplicationEventPublisher eventPublisher;

    public KnowledgeFailureService(KnowledgeDocService docService,
                                   KnowledgeParentChunkMapper parentChunkMapper,
                                   CourseService courseService,
                                   ApplicationEventPublisher eventPublisher) {
        this.docService = docService;
        this.parentChunkMapper = parentChunkMapper;
        this.courseService = courseService;
        this.eventPublisher = eventPublisher;
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
        return new FailureOutcome(true, false, "FAILED");
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
