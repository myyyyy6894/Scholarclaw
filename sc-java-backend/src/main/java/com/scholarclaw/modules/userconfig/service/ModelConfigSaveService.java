package com.scholarclaw.modules.userconfig.service;

import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户默认模型保存与已有课程迁移的统一事务边界（TASK-506 复核修复）。
 *
 * <p>事务内先锁定用户配置行，再重新统计活动课程并作最终迁移决策；建课也锁定同一行。
 * 这样不会出现“保存时统计为 0、探测期间另一请求用旧配置建课、最后新 Key 覆盖但课程未迁移”
 * 的竞态。覆盖默认 Key 与批量切换课程同成同败。
 */
@Service
public class ModelConfigSaveService {

    private final UserConfigService userConfigService;
    private final CourseEmbeddingMigrationPort migrationPort;
    private final UserCourseCountProvider courseCountProvider;

    public ModelConfigSaveService(UserConfigService userConfigService,
                                  CourseEmbeddingMigrationPort migrationPort,
                                  UserCourseCountProvider courseCountProvider) {
        this.userConfigService = userConfigService;
        this.migrationPort = migrationPort;
        this.courseCountProvider = courseCountProvider;
    }

    /**
     * @param migrateConfirmed   用户是否已确认迁移已有课程
     * @param embeddingModel     本次探测确认的 Embedding 模型
     * @param embeddingDim       本次探测得到的真实维度
     * @param expectedUpdatedAt  探测前配置快照的 updated_at；锁内比对，防止并发保存覆盖
     */
    @Transactional(rollbackFor = Exception.class)
    public UserConfigEntity saveAndMaybeMigrate(Long userId,
                                                UserConfigService.ByokModelUpdate update,
                                                boolean migrateConfirmed,
                                                String embeddingModel,
                                                int embeddingDim,
                                                LocalDateTime expectedUpdatedAt) {
        UserConfigEntity locked = userConfigService.lockForUpdate(userId);
        if (expectedUpdatedAt != null && !expectedUpdatedAt.equals(locked.getUpdatedAt())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT,
                    "模型配置已在其他会话中变更，请重新探测后再保存。");
        }
        boolean targetChanged = embeddingTargetChanged(locked, update);
        long existingCourses = courseCountProvider.countActiveCourses(userId);
        if (targetChanged && existingCourses > 0 && !migrateConfirmed) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "修改默认向量模型会影响已有 " + existingCourses
                            + " 门课程；请确认迁移已有课程后再保存。");
        }
        UserConfigEntity updated = userConfigService.updateByokModelConfig(userId, update);
        if (targetChanged && existingCourses > 0) {
            migrationPort.migrateExistingCourses(userId, update.embeddingProvider(),
                    embeddingModel, embeddingDim);
        }
        return updated;
    }

    private static boolean embeddingTargetChanged(UserConfigEntity locked,
                                                  UserConfigService.ByokModelUpdate update) {
        return !equalsTrimmed(locked.getDefaultEmbeddingProvider(), update.embeddingProvider())
                || !equalsTrimmed(locked.getEmbeddingBaseUrl(), update.embeddingBaseUrl())
                || !equalsTrimmed(locked.getDefaultEmbeddingModel(), update.embeddingModel());
    }

    private static boolean equalsTrimmed(String left, String right) {
        return left == null ? right == null : left.trim().equals(right == null ? null : right.trim());
    }
}
