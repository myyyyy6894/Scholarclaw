package com.scholarclaw.modules.course.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.course.entity.CourseNamespaceEntity;
import com.scholarclaw.modules.course.mapper.CourseNamespaceMapper;
import com.scholarclaw.modules.userconfig.service.CourseEmbeddingMigrationPort;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户默认 Embedding 变更时的课程批量迁移（TASK-506 复核修复）。
 *
 * <p>逐门调用 {@link CourseMutationService#applyEmbeddingChange}，与用户 Key 覆盖共用
 * 调用方事务：任一课程失败（例如正在 `REBUILDING`）整体回滚，不会出现“Key 已换、旧课程
 * 无法解密”的半成品状态。事件在事务提交后统一触发 REINDEX 与旧 Collection 清理。
 */
@Service
public class CourseEmbeddingMigrationService implements CourseEmbeddingMigrationPort {

    private final CourseNamespaceMapper courseNamespaceMapper;
    private final CourseMutationService courseMutationService;

    public CourseEmbeddingMigrationService(CourseNamespaceMapper courseNamespaceMapper,
                                           CourseMutationService courseMutationService) {
        this.courseNamespaceMapper = courseNamespaceMapper;
        this.courseMutationService = courseMutationService;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public int migrateExistingCourses(Long userId, String provider, String model, int dim) {
        List<CourseNamespaceEntity> courses = courseNamespaceMapper.selectList(
                Wrappers.<CourseNamespaceEntity>lambdaQuery()
                        .eq(CourseNamespaceEntity::getUserId, userId)
                        .eq(CourseNamespaceEntity::getIsDeleting, 0)
                        .orderByAsc(CourseNamespaceEntity::getCourseId));
        int migrated = 0;
        for (CourseNamespaceEntity course : courses) {
            try {
                courseMutationService.applyEmbeddingChange(userId, course.getCourseId(),
                        provider, model, dim, course.getIndexVersion());
                migrated++;
            } catch (BusinessException e) {
                if (e.getErrorCode() == ErrorCode.E_SYS_03) {
                    throw new BusinessException(ErrorCode.E_SYS_03,
                            "课程《" + course.getCourseName()
                                    + "》正在重建或状态已变化，无法迁移默认模型；"
                                    + "请等待重建完成后再保存。");
                }
                throw e;
            }
        }
        return migrated;
    }
}
