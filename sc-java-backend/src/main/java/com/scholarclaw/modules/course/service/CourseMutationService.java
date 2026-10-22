package com.scholarclaw.modules.course.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.scholarclaw.common.config.ScTimeZone;
import com.scholarclaw.common.enums.IndexStatus;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.common.event.CourseDeletingEvent;
import com.scholarclaw.common.event.CourseEmbeddingChangedEvent;
import com.scholarclaw.modules.course.entity.CourseNamespaceEntity;
import com.scholarclaw.modules.course.mapper.CourseNamespaceMapper;
import com.scholarclaw.modules.course.service.CourseDocumentStatsProvider.CourseDocumentStats;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 课程表本地事务边界（{@code 03} §4.2.1 / {@code 04} §4.3）。
 *
 * <p>把“事务内写库 + 发布同步领域事件”集中在这里，避免 {@code CourseService} 在长事务里
 * 做模型探测等网络 I/O。所有更新都带 {@code user_id} 条件，跨用户访问只会得到 0 行。
 */
@Service
public class CourseMutationService {

    private final CourseNamespaceMapper courseNamespaceMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectProvider<CourseDocumentStatsProvider> statsProvider;

    public CourseMutationService(CourseNamespaceMapper courseNamespaceMapper,
                                 ApplicationEventPublisher eventPublisher,
                                 ObjectProvider<CourseDocumentStatsProvider> statsProvider) {
        this.courseNamespaceMapper = courseNamespaceMapper;
        this.eventPublisher = eventPublisher;
        this.statsProvider = statsProvider;
    }

    @Transactional(rollbackFor = Exception.class)
    public CourseNamespaceEntity create(CourseNamespaceEntity entity) {
        courseNamespaceMapper.insert(entity);
        return courseNamespaceMapper.selectById(entity.getCourseId());
    }

    /**
     * 默认课程插入使用独立短事务：并发冲突时异常必须先把本事务回滚，
     * 调用方才能在事务外重新查询胜出线程写入的行。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public CourseNamespaceEntity insertDefault(CourseNamespaceEntity entity) {
        courseNamespaceMapper.insert(entity);
        return entity;
    }

    /**
     * 在单一事务内锁定课程行、读取真实文档统计、递增版本并发布事件。
     *
     * <p>统计必须在事务内读取：若在事务外先算 docCount，上传/删除并发时会把
     * “有文档”的课程错误地置为 `ACTIVE`。`SELECT ... FOR UPDATE` 与上传/删除共用同一行锁，
     * 保证决策与状态切换之间没有窗口。
     */
    @Transactional(rollbackFor = Exception.class)
    public EmbeddingChangeResult applyEmbeddingChange(Long userId, Long courseId,
                                                      String provider, String model, int dim,
                                                      int previousIndexVersion) {
        CourseNamespaceEntity locked = courseNamespaceMapper.selectActiveCourseForUpdate(userId, courseId);
        if (locked == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        if (IndexStatus.REBUILDING.name().equals(locked.getIndexStatus())) {
            throw new BusinessException(ErrorCode.E_SYS_03,
                    "当前课程索引正在重建中，请等待重建完成后再试。");
        }
        if (!Objects.equals(locked.getIndexVersion(), previousIndexVersion)) {
            throw new BusinessException(ErrorCode.E_SYS_03,
                    "当前课程索引状态已变化，请刷新后重试。");
        }
        CourseDocumentStatsProvider providerPort = statsProvider.getIfAvailable();
        if (providerPort == null) {
            throw new IllegalStateException("课程文档统计端口未实现，无法判断是否需要重建。");
        }
        CourseDocumentStats stats = providerPort
                .statsByCourse(userId, Map.of(courseId, previousIndexVersion))
                .getOrDefault(courseId, CourseDocumentStats.ZERO);
        String indexStatus = stats.total() > 0
                ? IndexStatus.REBUILDING.name() : IndexStatus.ACTIVE.name();
        int currentIndexVersion = previousIndexVersion + 1;

        int affected = courseNamespaceMapper.update(null, Wrappers.<CourseNamespaceEntity>lambdaUpdate()
                .eq(CourseNamespaceEntity::getCourseId, courseId)
                .eq(CourseNamespaceEntity::getUserId, userId)
                .eq(CourseNamespaceEntity::getIsDeleting, 0)
                .eq(CourseNamespaceEntity::getIndexVersion, previousIndexVersion)
                .set(CourseNamespaceEntity::getEmbeddingProvider, provider)
                .set(CourseNamespaceEntity::getEmbeddingModel, model)
                .set(CourseNamespaceEntity::getEmbeddingDim, dim)
                .set(CourseNamespaceEntity::getIndexStatus, indexStatus)
                .set(CourseNamespaceEntity::getUpdatedAt, ScTimeZone.now().toLocalDateTime())
                .setSql("index_version = index_version + 1"));
        if (affected != 1) {
            // 行锁已排除并发切换；这里兜底课程被删除等极端情况
            throw new BusinessException(ErrorCode.E_SYS_03,
                    "当前课程索引状态已变化，请刷新后重试。");
        }
        CourseNamespaceEntity updated = courseNamespaceMapper.selectById(courseId);
        eventPublisher.publishEvent(new CourseEmbeddingChangedEvent(
                userId, courseId, previousIndexVersion, currentIndexVersion,
                provider, model, dim));
        return new EmbeddingChangeResult(updated, stats.total());
    }

    @Transactional(rollbackFor = Exception.class)
    public void softDelete(Long userId, Long courseId) {
        int affected = courseNamespaceMapper.update(null, Wrappers.<CourseNamespaceEntity>lambdaUpdate()
                .eq(CourseNamespaceEntity::getCourseId, courseId)
                .eq(CourseNamespaceEntity::getUserId, userId)
                .eq(CourseNamespaceEntity::getIsDeleting, 0)
                .set(CourseNamespaceEntity::getIsDeleting, 1)
                .set(CourseNamespaceEntity::getUpdatedAt, ScTimeZone.now().toLocalDateTime()));
        if (affected != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        eventPublisher.publishEvent(new CourseDeletingEvent(userId, courseId));
    }

    /** 切换结果：更新后的课程行 + 事务内统计到的待重建文档数。 */
    public record EmbeddingChangeResult(CourseNamespaceEntity course, int reindexingDocCount) {
    }
}
