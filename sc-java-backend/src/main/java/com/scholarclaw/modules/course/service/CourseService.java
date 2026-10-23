package com.scholarclaw.modules.course.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.scholarclaw.common.client.ModelEndpoint;
import com.scholarclaw.common.client.ModelProbeType;
import com.scholarclaw.common.client.PythonAiEngineClient;
import com.scholarclaw.common.config.ScTimeZone;
import com.scholarclaw.common.enums.IndexStatus;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.common.security.AesGcmCryptoService;
import com.scholarclaw.common.security.BaseUrlSecurityValidator;
import com.scholarclaw.modules.course.dto.ChangeEmbeddingModelRequest;
import com.scholarclaw.modules.course.dto.ChangeEmbeddingModelVO;
import com.scholarclaw.modules.course.dto.CourseNamespaceVO;
import com.scholarclaw.modules.course.dto.CreateCourseRequest;
import com.scholarclaw.modules.course.dto.DeleteCourseVO;
import com.scholarclaw.modules.course.entity.CourseNamespaceEntity;
import com.scholarclaw.modules.course.mapper.CourseNamespaceMapper;
import com.scholarclaw.modules.course.service.CourseDocumentStatsProvider.CourseDocumentStats;
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import com.scholarclaw.modules.userconfig.service.ModelConfigRules;
import com.scholarclaw.modules.userconfig.service.ModelKeyBindingRules;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import com.scholarclaw.modules.userconfig.service.UserCourseCountProvider;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 课程空间与 Embedding 绑定（{@code 06} TASK-401、{@code 04} §4.3、{@code 03} §4.2）。
 *
 * <p><b>Single-Writer：</b>本类是 {@code t_course_namespace} 的唯一业务入口；不读写
 * {@code t_knowledge_doc} / {@code t_ddl_task}，文档计数通过 {@link CourseDocumentStatsProvider}
 * 端口获取，删除/切换通过领域事件通知其他模块。
 *
 * <p><b>不伪造维度：</b>创建课程只复制用户在 `API-USER-003` 中探测过的 Embedding 三元组；
 * 切换模型必须重新调用 Python 探测并拿到 {@code actual_dim > 0} 后才落库。
 */
@Service
public class CourseService implements UserCourseCountProvider {

    private static final String DEFAULT_COURSE_NAME = "未分类课程";
    private static final String DEFAULT_DESCRIPTION = "系统自动初始化的默认未分类课件归档空间";
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    private final CourseNamespaceMapper courseNamespaceMapper;
    private final CourseMutationService courseMutationService;
    private final UserConfigService userConfigService;
    private final ModelConfigRules modelConfigRules;
    private final ModelKeyBindingRules modelKeyBindingRules;
    private final PythonAiEngineClient pythonAiEngineClient;
    private final BaseUrlSecurityValidator baseUrlSecurityValidator;
    private final AesGcmCryptoService cryptoService;
    private final ObjectProvider<CourseDocumentStatsProvider> statsProvider;

    public CourseService(CourseNamespaceMapper courseNamespaceMapper,
                         CourseMutationService courseMutationService,
                         UserConfigService userConfigService,
                         ModelConfigRules modelConfigRules,
                         ModelKeyBindingRules modelKeyBindingRules,
                         PythonAiEngineClient pythonAiEngineClient,
                         BaseUrlSecurityValidator baseUrlSecurityValidator,
                         AesGcmCryptoService cryptoService,
                         ObjectProvider<CourseDocumentStatsProvider> statsProvider) {
        this.courseNamespaceMapper = courseNamespaceMapper;
        this.courseMutationService = courseMutationService;
        this.userConfigService = userConfigService;
        this.modelConfigRules = modelConfigRules;
        this.modelKeyBindingRules = modelKeyBindingRules;
        this.pythonAiEngineClient = pythonAiEngineClient;
        this.baseUrlSecurityValidator = baseUrlSecurityValidator;
        this.cryptoService = cryptoService;
        this.statsProvider = statsProvider;
    }

    /** `API-COURSE-001`：当前用户活动课程列表（默认课程排在最后）。 */
    public List<CourseNamespaceVO> listCourses(Long userId) {
        List<CourseNamespaceEntity> courses = courseNamespaceMapper.selectList(
                Wrappers.<CourseNamespaceEntity>lambdaQuery()
                        .eq(CourseNamespaceEntity::getUserId, userId)
                        .eq(CourseNamespaceEntity::getIsDeleting, 0)
                        .orderByAsc(CourseNamespaceEntity::getIsDefault)
                        .orderByDesc(CourseNamespaceEntity::getCreatedAt));
        if (courses.isEmpty()) {
            return List.of();
        }
        ZoneId zone = zoneFor(userId);
        Map<Long, CourseDocumentStats> stats = statsFor(userId,
                courses.stream().collect(Collectors.toMap(
                        CourseNamespaceEntity::getCourseId,
                        course -> course.getIndexVersion() == null ? 1 : course.getIndexVersion())));
        return courses.stream()
                .map(course -> toVO(course,
                        stats.getOrDefault(course.getCourseId(), CourseDocumentStats.ZERO), zone))
                .toList();
    }

    /** `API-COURSE-002`：新建普通课程，锁定用户当前已验证的 Embedding 三元组。 */
    public CourseNamespaceVO createCourse(Long userId, CreateCourseRequest request) {
        String courseName = request.courseName().trim();
        if (DEFAULT_COURSE_NAME.equals(courseName)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "“未分类课程”是系统默认课程保留名称，请更换课程名称。");
        }
        if (existsActiveCourseName(userId, courseName)) {
            throw new BusinessException(ErrorCode.COURSE_NAME_DUPLICATE);
        }
        try {
            // 与默认模型保存共用用户级行锁：锁内读取配置，避免并发保存新 Key 时不迁移本课程
            CourseNamespaceEntity created = courseMutationService.createWithUserLock(userId, config -> {
                requireValidEmbeddingConfig(config);
                return buildCourse(userId, courseName, trimToNull(request.description()), 0, config);
            });
            return toVO(created, CourseDocumentStats.ZERO, zoneFor(userId));
        } catch (DuplicateKeyException e) {
            // uk_course_user_active_name 并发兜底
            throw new BusinessException(ErrorCode.COURSE_NAME_DUPLICATE);
        }
    }

    /** `API-COURSE-003`：探测新模型成功后原子递增索引版本；旧配置在探测失败时不受影响。 */
    public ChangeEmbeddingModelVO changeEmbeddingModel(Long userId, Long courseId,
                                                        ChangeEmbeddingModelRequest request) {
        CourseNamespaceEntity course = getActiveCourse(userId, courseId);
        if (IndexStatus.REBUILDING.name().equals(course.getIndexStatus())) {
            throw new BusinessException(ErrorCode.E_SYS_03,
                    "当前课程索引正在重建中，请等待重建完成后再试。");
        }

        UserConfigEntity config = requireActiveConfig(userId);
        requireValidEmbeddingConfig(config);
        String provider;
        String model;
        if (request.usesUserDefault()) {
            provider = config.getDefaultEmbeddingProvider();
            model = config.getDefaultEmbeddingModel();
        } else {
            provider = trimToNull(request.embeddingProvider());
            model = trimToNull(request.embeddingModel());
            if (provider == null || model == null) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "use_user_default=false 时必须提供 embedding_provider 与 embedding_model。");
            }
            if (!modelConfigRules.isProviderAllowedForProbe("EMBEDDING", provider)) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "embedding_provider 仅允许 OPENAI_COMPATIBLE/ZHIPU/OLLAMA。");
            }
            // 已保存 Key 的目标绑定：provider + Origin + 模型名任一变化都必须在解密前拒绝
            modelKeyBindingRules.requireSameTarget(
                    config.getDefaultEmbeddingProvider(), config.getEmbeddingBaseUrl(),
                    config.getDefaultEmbeddingModel(),
                    provider, config.getEmbeddingBaseUrl(), model);
        }
        if (!hasText(config.getEmbeddingBaseUrl())) {
            throw new BusinessException(ErrorCode.E_SYS_01);
        }
        baseUrlSecurityValidator.validate(config.getEmbeddingBaseUrl());
        String apiKey = resolveEmbeddingKey(config, provider);
        ModelEndpoint endpoint = new ModelEndpoint(
                provider, config.getEmbeddingBaseUrl(), apiKey, model);
        int dim = probeEmbeddingDimension(userId, endpoint);

        int previousVersion = course.getIndexVersion();
        CourseMutationService.EmbeddingChangeResult result = courseMutationService
                .applyEmbeddingChange(userId, courseId, provider, model, dim, previousVersion);
        return new ChangeEmbeddingModelVO(courseId, previousVersion, result.course().getIndexVersion(),
                result.course().getIndexStatus(), provider, model, dim,
                result.reindexingDocCount());
    }

    /** 解析回调/文档删除后收敛课程索引状态（TASK-505，TASK-506 复核修复）。 */
    public void refreshIndexStatus(Long userId, Long courseId, int indexVersion) {
        courseMutationService.refreshIndexStatus(userId, courseId, indexVersion);
    }

    /** 失败文档重试/重建时把 `REBUILD_FAILED` 拉回 `REBUILDING`。 */
    public void markRebuildingIfFailed(Long userId, Long courseId) {
        courseMutationService.markRebuildingIfFailed(userId, courseId);
    }

    /** `API-COURSE-004`：第一阶段软删，事件监听方负责文档/DDL/会话；物理清理由 GC 兜底。 */
    public DeleteCourseVO deleteCourse(Long userId, Long courseId) {
        getActiveCourse(userId, courseId);
        courseMutationService.softDelete(userId, courseId);
        return new DeleteCourseVO(courseId);
    }

    /**
     * 默认【未分类课程】懒加载（{@code 03} §4.2.1）。
     *
     * <p>并发安全由 {@code uk_course_user_default} 唯一索引兜底：插入冲突后重新查询胜出线程
     * 写入的行；绝不以“先查后插”声称并发安全。
     */
    public CourseNamespaceEntity getOrCreateDefaultCourse(Long userId) {
        CourseNamespaceEntity existing = courseNamespaceMapper.selectActiveDefaultCourse(userId);
        if (existing != null) {
            return existing;
        }
        try {
            // 不存在时才进入用户行锁：锁内重查并读取配置，与模型保存/普通建课互斥
            return courseMutationService.getOrCreateDefaultWithUserLock(userId, config -> {
                requireValidEmbeddingConfig(config);
                return buildCourse(userId, DEFAULT_COURSE_NAME, DEFAULT_DESCRIPTION, 1, config);
            });
        } catch (DuplicateKeyException e) {
            CourseNamespaceEntity winner = courseNamespaceMapper.selectActiveDefaultCourse(userId);
            if (winner != null) {
                return winner;
            }
            throw e;
        }
    }

    /** `userconfig` 的只读端口：`API-USER-003` 的 `existing_course_count`。 */
    @Override
    public int countActiveCourses(Long userId) {
        long count = courseNamespaceMapper.selectCount(Wrappers.<CourseNamespaceEntity>lambdaQuery()
                .eq(CourseNamespaceEntity::getUserId, userId)
                .eq(CourseNamespaceEntity::getIsDeleting, 0));
        return (int) Math.min(count, Integer.MAX_VALUE);
    }

    /** 课程归属与活动状态校验；跨用户/已删除课程统一 404（防 IDOR）。 */
    public CourseNamespaceEntity getActiveCourse(Long userId, Long courseId) {
        CourseNamespaceEntity course = courseNamespaceMapper.selectOne(
                Wrappers.<CourseNamespaceEntity>lambdaQuery()
                        .eq(CourseNamespaceEntity::getCourseId, courseId)
                        .eq(CourseNamespaceEntity::getUserId, userId)
                        .eq(CourseNamespaceEntity::getIsDeleting, 0));
        if (course == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return course;
    }

    /**
     * 事务内锁定课程行（调用方必须已开启事务）；用于上传与课程状态变更的互斥。
     */
    public CourseNamespaceEntity getActiveCourseForUpdate(Long userId, Long courseId) {
        CourseNamespaceEntity course = courseNamespaceMapper.selectActiveCourseForUpdate(userId, courseId);
        if (course == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return course;
    }

    private UserConfigEntity requireActiveConfig(Long userId) {
        UserConfigEntity config = userConfigService.findById(userId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.AUTH_UNAUTHORIZED, "登录状态已失效，请重新登录。"));
        if (UserConfigService.isDeactivating(config)) {
            throw new BusinessException(ErrorCode.ACCOUNT_DEACTIVATED);
        }
        return config;
    }

    private void requireValidEmbeddingConfig(UserConfigEntity config) {
        if (!modelConfigRules.isEmbeddingConfigValid(config)) {
            throw new BusinessException(ErrorCode.E_SYS_01,
                    "⚠️ 尚未配置完整的向量模型（Embedding），请先前往【个人设置 -> 模型配置】完成配置。");
        }
    }

    private boolean existsActiveCourseName(Long userId, String courseName) {
        return courseNamespaceMapper.selectCount(Wrappers.<CourseNamespaceEntity>lambdaQuery()
                .eq(CourseNamespaceEntity::getUserId, userId)
                .eq(CourseNamespaceEntity::getCourseName, courseName)
                .eq(CourseNamespaceEntity::getIsDeleting, 0)) > 0;
    }

    private CourseNamespaceEntity buildCourse(Long userId, String courseName, String description,
                                              int isDefault, UserConfigEntity config) {
        LocalDateTime now = ScTimeZone.now().toLocalDateTime();
        CourseNamespaceEntity entity = new CourseNamespaceEntity();
        entity.setUserId(userId);
        entity.setCourseName(courseName);
        entity.setDescription(description);
        entity.setIsDefault(isDefault);
        entity.setEmbeddingProvider(config.getDefaultEmbeddingProvider());
        entity.setEmbeddingModel(config.getDefaultEmbeddingModel());
        entity.setEmbeddingDim(config.getDefaultEmbeddingDim());
        entity.setIndexVersion(1);
        entity.setIndexStatus(IndexStatus.ACTIVE.name());
        entity.setIsDeleting(0);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    private String resolveEmbeddingKey(UserConfigEntity config, String provider) {
        // 白名单免密 Ollama：始终发送无 Key 请求，即使库里残留旧密文也不解密外发
        if (modelConfigRules.isWhitelistedLocalOllama(provider, config.getEmbeddingBaseUrl())) {
            return null;
        }
        if (config.getEmbeddingApiKeyCipher() == null) {
            throw new BusinessException(ErrorCode.E_SYS_01);
        }
        try {
            return cryptoService.decrypt(config.getEmbeddingApiKeyCipher());
        } catch (BusinessException e) {
            throw new BusinessException(ErrorCode.E_SYS_01,
                    "Embedding API Key 无法解密，请重新保存模型配置。");
        }
    }

    private int probeEmbeddingDimension(Long userId, ModelEndpoint endpoint) {
        PythonAiEngineClient.ProbeResult result = pythonAiEngineClient.probeModel(
                userId, ModelProbeType.EMBEDDING, null, endpoint);
        if (!result.embeddingReachable() || result.actualDim() == null || result.actualDim() <= 0) {
            throw new BusinessException(ErrorCode.UPSTREAM_MODEL_ERROR,
                    "模型探测未通过，原课程配置保持不变。");
        }
        return result.actualDim();
    }

    private Map<Long, CourseDocumentStats> statsFor(Long userId,
                                                    Map<Long, Integer> courseIndexVersions) {
        CourseDocumentStatsProvider provider = statsProvider.getIfAvailable();
        if (provider == null) {
            throw new IllegalStateException("课程文档统计端口未实现，无法返回真实统计。");
        }
        if (courseIndexVersions.isEmpty()) {
            return Map.of();
        }
        // 统计读取失败必须显式失败（500），不得静默返回全 0 冒充真实数据
        return Objects.requireNonNullElse(
                provider.statsByCourse(userId, courseIndexVersions), Map.of());
    }

    private CourseNamespaceVO toVO(CourseNamespaceEntity entity, CourseDocumentStats stats, ZoneId zone) {
        return new CourseNamespaceVO(
                entity.getCourseId(),
                entity.getCourseName(),
                entity.getDescription(),
                entity.getIsDefault() != null && entity.getIsDefault() == 1,
                entity.getEmbeddingProvider(),
                entity.getEmbeddingModel(),
                entity.getEmbeddingDim() == null ? 0 : entity.getEmbeddingDim(),
                entity.getIndexVersion() == null ? 1 : entity.getIndexVersion(),
                entity.getIndexStatus(),
                stats.ready(),
                stats.parsing(),
                stats.failed(),
                formatTimestamp(entity.getCreatedAt(), zone),
                formatTimestamp(entity.getUpdatedAt(), zone));
    }

    private ZoneId zoneFor(Long userId) {
        return userConfigService.findById(userId)
                .map(config -> {
                    try {
                        return ZoneId.of(config.getTimezone());
                    } catch (RuntimeException e) {
                        return ScTimeZone.zoneId();
                    }
                })
                .orElseGet(ScTimeZone::zoneId);
    }

    private static String formatTimestamp(LocalDateTime value, ZoneId zone) {
        return value == null ? null : value.atZone(zone).toOffsetDateTime().format(TIMESTAMP_FORMAT);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
