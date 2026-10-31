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
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
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
    private final UserConfigService userConfigService;
    private final CourseDeletionReceiptService receiptService;

    public CourseMutationService(CourseNamespaceMapper courseNamespaceMapper,
                                 ApplicationEventPublisher eventPublisher,
                                 ObjectProvider<CourseDocumentStatsProvider> statsProvider,
                                 UserConfigService userConfigService,
                                 CourseDeletionReceiptService receiptService) {
        this.courseNamespaceMapper = courseNamespaceMapper;
        this.eventPublisher = eventPublisher;
        this.statsProvider = statsProvider;
        this.userConfigService = userConfigService;
        this.receiptService = receiptService;
    }

    @Transactional(rollbackFor = Exception.class)
    public CourseNamespaceEntity create(CourseNamespaceEntity entity) {
        courseNamespaceMapper.insert(entity);
        return courseNamespaceMapper.selectById(entity.getCourseId());
    }

    /**
     * 默认课程懒加载与模型保存共用用户级行锁（TASK-506 二轮复核）。
     *
     * <p>锁内重新查询默认课程并读取配置，避免在“保存新 Key 但不迁移”的并发窗口里创建
     * 绑定旧模型的默认课程。
     */
    @Transactional(rollbackFor = Exception.class)
    public CourseNamespaceEntity getOrCreateDefaultWithUserLock(
            Long userId, Function<UserConfigEntity, CourseNamespaceEntity> factory) {
        userConfigService.lockForUpdate(userId);
        CourseNamespaceEntity existing = courseNamespaceMapper.selectActiveDefaultCourse(userId);
        if (existing != null) {
            return existing;
        }
        UserConfigEntity config = userConfigService.findById(userId)
                .orElseThrow(() -> new com.scholarclaw.common.exception.BusinessException(
                        com.scholarclaw.common.exception.ErrorCode.AUTH_UNAUTHORIZED,
                        "登录状态已失效，请重新登录。"));
        CourseNamespaceEntity entity = factory.apply(config);
        courseNamespaceMapper.insert(entity);
        return courseNamespaceMapper.selectById(entity.getCourseId());
    }

    /**
     * 建课与用户默认模型保存共用用户级行锁（TASK-506 复核修复）。
     *
     * <p>锁住 `t_user_config` 后再读取并复制 Embedding 三元组，保证不会在“保存新 Key 但不迁移”
     * 的并发窗口里创建出绑定旧模型的课程。
     */
    @Transactional(rollbackFor = Exception.class)
    public CourseNamespaceEntity createWithUserLock(
            Long userId, Function<UserConfigEntity, CourseNamespaceEntity> factory) {
        userConfigService.lockForUpdate(userId);
        UserConfigEntity config = userConfigService.findById(userId)
                .orElseThrow(() -> new com.scholarclaw.common.exception.BusinessException(
                        com.scholarclaw.common.exception.ErrorCode.AUTH_UNAUTHORIZED,
                        "登录状态已失效，请重新登录。"));
        CourseNamespaceEntity entity = factory.apply(config);
        courseNamespaceMapper.insert(entity);
        return courseNamespaceMapper.selectById(entity.getCourseId());
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

    /**
     * 解析回调/删除后收敛课程索引状态（TASK-505，TASK-506 复核修复）。
     *
     * <p>允许从 `REBUILDING` 或 `REBUILD_FAILED` 基于真实统计收敛：仍有解析中文档则回到
     * `REBUILDING`；全部就绪或空课程 → `ACTIVE`；仍有失败 → `REBUILD_FAILED`。
     * 版本条件避免旧回调把新课程误置。使用条件更新而不是行锁，避免与课程切换的锁顺序冲突。
     *
     * @return 本次调用是否把课程从重建态收敛为 {@code ACTIVE}（用于 TASK-802 的“重建完成”通知；
     *         并发下只有真正完成状态更新的那一次返回 true）
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean refreshIndexStatus(Long userId, Long courseId, int indexVersion) {
        CourseNamespaceEntity current = courseNamespaceMapper.selectOne(
                Wrappers.<CourseNamespaceEntity>lambdaQuery()
                        .eq(CourseNamespaceEntity::getCourseId, courseId)
                        .eq(CourseNamespaceEntity::getUserId, userId)
                        .eq(CourseNamespaceEntity::getIsDeleting, 0));
        if (current == null || !Objects.equals(current.getIndexVersion(), indexVersion)) {
            return false;
        }
        String currentStatus = current.getIndexStatus();
        if (!IndexStatus.REBUILDING.name().equals(currentStatus)
                && !IndexStatus.REBUILD_FAILED.name().equals(currentStatus)) {
            return false;
        }
        CourseDocumentStatsProvider providerPort = statsProvider.getIfAvailable();
        if (providerPort == null) {
            return false;
        }
        CourseDocumentStats stats = providerPort
                .statsByCourse(userId, Map.of(courseId, indexVersion))
                .getOrDefault(courseId, CourseDocumentStats.ZERO);
        String status;
        if (stats.parsing() > 0) {
            status = IndexStatus.REBUILDING.name();
        } else {
            status = stats.total() == 0 || stats.ready() >= stats.total()
                    ? IndexStatus.ACTIVE.name() : IndexStatus.REBUILD_FAILED.name();
        }
        int updated = courseNamespaceMapper.update(null,
                Wrappers.<CourseNamespaceEntity>lambdaUpdate()
                        .eq(CourseNamespaceEntity::getCourseId, courseId)
                        .eq(CourseNamespaceEntity::getUserId, userId)
                        .eq(CourseNamespaceEntity::getIndexVersion, indexVersion)
                        .in(CourseNamespaceEntity::getIndexStatus,
                                IndexStatus.REBUILDING.name(), IndexStatus.REBUILD_FAILED.name())
                        .set(CourseNamespaceEntity::getIndexStatus, status)
                        .set(CourseNamespaceEntity::getUpdatedAt,
                                ScTimeZone.now().toLocalDateTime()));
        return updated == 1 && IndexStatus.ACTIVE.name().equals(status);
    }

    /** 失败文档重新进入解析时把课程从 `REBUILD_FAILED` 拉回 `REBUILDING`，为后续收敛提供起点。 */
    @Transactional(rollbackFor = Exception.class)
    public void markRebuildingIfFailed(Long userId, Long courseId) {
        courseNamespaceMapper.update(null, Wrappers.<CourseNamespaceEntity>lambdaUpdate()
                .eq(CourseNamespaceEntity::getCourseId, courseId)
                .eq(CourseNamespaceEntity::getUserId, userId)
                .eq(CourseNamespaceEntity::getIsDeleting, 0)
                .eq(CourseNamespaceEntity::getIndexStatus, IndexStatus.REBUILD_FAILED.name())
                .set(CourseNamespaceEntity::getIndexStatus, IndexStatus.REBUILDING.name())
                .set(CourseNamespaceEntity::getUpdatedAt, ScTimeZone.now().toLocalDateTime()));
    }

    @Transactional(rollbackFor = Exception.class)
    public SoftDeleteOutcome softDelete(Long userId, Long courseId) {
        // 先锁定课程行（不过滤 is_deleting），与 DDL 建单/编辑/确认使用的
        // getActiveCourseForUpdate 串行化。并发重复删除时，后到请求看到 is_deleting=1
        // 即返回 ALREADY_DELETING，而不是 404。
        CourseNamespaceEntity existing = courseNamespaceMapper.selectCourseForUpdate(userId,
                courseId);
        if (existing == null) {
            return SoftDeleteOutcome.NOT_FOUND;
        }
        if (existing.getIsDeleting() != null && existing.getIsDeleting() == 1) {
            return SoftDeleteOutcome.ALREADY_DELETING;
        }
        int affected = courseNamespaceMapper.update(null, Wrappers.<CourseNamespaceEntity>lambdaUpdate()
                .eq(CourseNamespaceEntity::getCourseId, courseId)
                .eq(CourseNamespaceEntity::getUserId, userId)
                .eq(CourseNamespaceEntity::getIsDeleting, 0)
                .set(CourseNamespaceEntity::getIsDeleting, 1)
                .set(CourseNamespaceEntity::getUpdatedAt, ScTimeZone.now().toLocalDateTime()));
        if (affected != 1) {
            return SoftDeleteOutcome.ALREADY_DELETING;
        }
        // 回执与软删同事务：课程行稍后被物理删除，回执保证重复删除仍可返回原结果。
        receiptService.recordDeleting(userId, courseId);
        eventPublisher.publishEvent(new CourseDeletingEvent(userId, courseId));
        return SoftDeleteOutcome.DELETED;
    }

    /** 软删结果：区分“本次删除”“已处于删除中”“不存在”，供删除接口幂等返回。 */
    public enum SoftDeleteOutcome {
        DELETED, ALREADY_DELETING, NOT_FOUND
    }

    /** GC 兜底：物理删除已软删课程行；条件限定 `is_deleting=1`，重复执行幂等。 */
    @Transactional(rollbackFor = Exception.class)
    public int purgeSoftDeleted(Long userId, Long courseId) {
        int deleted = courseNamespaceMapper.delete(Wrappers.<CourseNamespaceEntity>lambdaQuery()
                .eq(CourseNamespaceEntity::getCourseId, courseId)
                .eq(CourseNamespaceEntity::getUserId, userId)
                .eq(CourseNamespaceEntity::getIsDeleting, 1));
        if (deleted > 0) {
            receiptService.markDeleted(userId, courseId);
        }
        return deleted;
    }

    /** 删除接口幂等判定：该用户是否已删除过该课程（课程行可能已物理删除）。 */
    public boolean hasDeletionReceipt(Long userId, Long courseId) {
        return receiptService.exists(userId, courseId);
    }

    /** 切换结果：更新后的课程行 + 事务内统计到的待重建文档数。 */
    public record EmbeddingChangeResult(CourseNamespaceEntity course, int reindexingDocCount) {
    }
}
