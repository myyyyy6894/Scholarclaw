package com.scholarclaw.modules.course.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.scholarclaw.common.config.ScTimeZone;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.course.entity.CourseNamespaceEntity;
import com.scholarclaw.modules.course.event.CourseDeletingEvent;
import com.scholarclaw.modules.course.event.CourseEmbeddingChangedEvent;
import com.scholarclaw.modules.course.mapper.CourseNamespaceMapper;
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

    public CourseMutationService(CourseNamespaceMapper courseNamespaceMapper,
                                 ApplicationEventPublisher eventPublisher) {
        this.courseNamespaceMapper = courseNamespaceMapper;
        this.eventPublisher = eventPublisher;
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

    @Transactional(rollbackFor = Exception.class)
    public CourseNamespaceEntity applyEmbeddingChange(Long userId, Long courseId,
                                                      String provider, String model, int dim,
                                                      int previousIndexVersion, int currentIndexVersion,
                                                      String indexStatus) {
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
            // 另一个请求已切换/课程被删除：旧版本号条件不再命中，拒绝覆盖
            throw new BusinessException(ErrorCode.E_SYS_03,
                    "当前课程索引状态已变化，请刷新后重试。");
        }
        CourseNamespaceEntity updated = courseNamespaceMapper.selectById(courseId);
        eventPublisher.publishEvent(new CourseEmbeddingChangedEvent(
                userId, courseId, previousIndexVersion, currentIndexVersion,
                provider, model, dim));
        return updated;
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
}
