package com.scholarclaw.modules.userconfig.controller;

import com.scholarclaw.common.client.ModelEndpoint;
import com.scholarclaw.common.client.ModelProbeType;
import com.scholarclaw.common.client.PythonAiEngineClient;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.common.response.Result;
import com.scholarclaw.common.security.AesGcmCryptoService;
import com.scholarclaw.common.security.BaseUrlSecurityValidator;
import com.scholarclaw.common.security.SecurityContextUtils;
import com.scholarclaw.modules.userconfig.dto.DeactivateAccountRequest;
import com.scholarclaw.modules.userconfig.dto.ModelConfigUpdateRequest;
import com.scholarclaw.modules.userconfig.dto.ModelProbeRequest;
import com.scholarclaw.modules.userconfig.dto.ModelProbeVO;
import com.scholarclaw.modules.userconfig.dto.PreferencesRequest;
import com.scholarclaw.modules.userconfig.dto.UserConfigVO;
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import com.scholarclaw.modules.userconfig.service.ModelConfigRules;
import com.scholarclaw.modules.userconfig.service.ModelConfigSaveService;
import com.scholarclaw.modules.userconfig.service.ModelKeyBindingRules;
import com.scholarclaw.modules.userconfig.service.PlatformEmbeddingDefaults;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import com.scholarclaw.modules.userconfig.service.UserCourseCountProvider;
import com.scholarclaw.modules.userconfig.service.UserDeactivationService;
import jakarta.validation.Valid;
import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户配置与 BYOK 接口（{@code 04} §4.2：`API-USER-001~005`）。
 *
 * <p>身份**只**来自 JWT（{@link SecurityContextUtils#requireCurrentUserId()}），
 * 任何接口都不接收 `user_id` 参数——即使客户端在 Query/Body 里塞了 `user_id` 也不会被读取
 * （{@code 04} §2.7.2 权限判断铁律）。
 *
 * <p><b>明文 Key 的生命周期：</b>只在本方法栈内存在——要么来自请求体，要么从密文解密；
 * 用于一次探测与一次加密后即丢弃，不写日志、不入缓存、不出现在异常消息里。
 */
@RestController
@RequestMapping("/api/v1/user")
public class UserConfigController {

    private static final Logger log = LoggerFactory.getLogger(UserConfigController.class);

    /**
     * 已有课程数量（`API-USER-003` 响应附加字段）。
     *
     * <p>课程模块（`t_course_namespace` 的 Single-Writer）尚未落地，当前系统不可能存在课程，
     * 因此这里的 0 是事实而非占位。课程模块上线后必须改为经该模块的只读能力查询——
     * `userconfig` 不得直接访问 `t_course_namespace`（{@code 02} §4.1 Strict DAG）。
     */
    private static final String PROBE_UPSTREAM_FAILED = "模型探测未通过，原配置保持不变。";
    private static final Set<String> IM_PLATFORMS = Set.of("QQ_ONEBOT", "WECOM");

    private final UserConfigService userConfigService;
    private final AesGcmCryptoService cryptoService;
    private final BaseUrlSecurityValidator baseUrlSecurityValidator;
    private final PythonAiEngineClient pythonAiEngineClient;
    private final ModelConfigRules modelConfigRules;
    private final ModelKeyBindingRules modelKeyBindingRules;
    private final UserCourseCountProvider userCourseCountProvider;
    private final ModelConfigSaveService modelConfigSaveService;
    private final UserDeactivationService userDeactivationService;
    private final PlatformEmbeddingDefaults platformEmbeddingDefaults;

    public UserConfigController(UserConfigService userConfigService,
                                AesGcmCryptoService cryptoService,
                                BaseUrlSecurityValidator baseUrlSecurityValidator,
                                PythonAiEngineClient pythonAiEngineClient,
                                ModelConfigRules modelConfigRules,
                                ModelKeyBindingRules modelKeyBindingRules,
                                UserCourseCountProvider userCourseCountProvider,
                                ModelConfigSaveService modelConfigSaveService,
                                UserDeactivationService userDeactivationService,
                                PlatformEmbeddingDefaults platformEmbeddingDefaults) {
        this.userConfigService = userConfigService;
        this.cryptoService = cryptoService;
        this.baseUrlSecurityValidator = baseUrlSecurityValidator;
        this.pythonAiEngineClient = pythonAiEngineClient;
        this.modelConfigRules = modelConfigRules;
        this.modelKeyBindingRules = modelKeyBindingRules;
        this.userCourseCountProvider = userCourseCountProvider;
        this.modelConfigSaveService = modelConfigSaveService;
        this.userDeactivationService = userDeactivationService;
        this.platformEmbeddingDefaults = platformEmbeddingDefaults;
    }

    /** 统一出口：把「平台是否提供向量模型」带进响应，避免各端点各写一遍。 */
    private UserConfigVO vo(UserConfigEntity entity, Integer existingCourseCount) {
        return toVO(entity, cryptoService, modelConfigRules, existingCourseCount,
                platformEmbeddingDefaults.isEnabled());
    }

    /** `API-USER-001`：查询当前用户配置与脱敏密钥视图。 */
    @GetMapping("/config")
    public Result<UserConfigVO> getCurrentUserConfig() {
        Long userId = SecurityContextUtils.requireCurrentUserId();
        return Result.ok("操作成功", vo(currentEntity(), userCourseCountProvider.countActiveCourses(userId)));
    }

    /** `API-USER-002`：模型连通性与真实维度探测（无副作用，不写库）。 */
    @PostMapping("/model-config/probe")
    public Result<ModelProbeVO> probeModel(@Valid @RequestBody ModelProbeRequest request) {
        Long userId = SecurityContextUtils.requireCurrentUserId();
        UserConfigEntity current = currentEntity();
        ModelProbeType probeType = ModelProbeType.valueOf(request.probeType());
        if (probeType == ModelProbeType.BOTH) {
            // DTO 的 @Pattern 已拦截；这里再挡一层，防止将来有人绕过校验直接调用
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "公共探测接口只支持 CHAT 或 EMBEDDING。");
        }
        if (!modelConfigRules.isProviderAllowedForProbe(probeType.name(), request.provider())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "当前探测类型不支持该供应商：EMBEDDING 仅允许 OPENAI_COMPATIBLE/ZHIPU/OLLAMA。");
        }

        // Java 侧第一道 SSRF 校验（04 §4.2.2 规则 1）；Python 出站前还会做 DNS 解析后的二次校验
        baseUrlSecurityValidator.validate(request.baseUrl());

        String providedKey = normalizeSecret(request.apiKey());
        // 惰性解密：只有"未提供新 Key 且明确要求用已保存 Key"时才碰旧密文。
        // 且必须先通过目标绑定校验：旧 Key 不能被发往用户本次改成的任意地址/供应商/模型。
        boolean useSavedKey = request.useSavedKey() && providedKey == null;
        String savedChatKey = null;
        if (probeType.includesChat() && useSavedKey) {
            savedChatKey = readSavedKey(current.getChatApiKeyCipher(),
                    current.getChatProvider(), current.getChatBaseUrl(), current.getChatModel(),
                    request.provider(), request.baseUrl(), request.modelName(), "对话模型");
        }
        String savedEmbeddingKey = null;
        if (probeType.includesEmbedding() && useSavedKey) {
            savedEmbeddingKey = readSavedKey(current.getEmbeddingApiKeyCipher(),
                    current.getDefaultEmbeddingProvider(), current.getEmbeddingBaseUrl(),
                    current.getDefaultEmbeddingModel(),
                    request.provider(), request.baseUrl(), request.modelName(), "向量模型");
        }

        ModelEndpoint chatEndpoint = null;
        if (probeType.includesChat()) {
            String key = resolveProbeKey(providedKey, request.useSavedKey(), savedChatKey,
                    request.provider(), request.baseUrl());
            chatEndpoint = new ModelEndpoint(request.provider(), request.baseUrl(), key, request.modelName());
        }
        ModelEndpoint embeddingEndpoint = null;
        if (probeType.includesEmbedding()) {
            String key = resolveProbeKey(providedKey, request.useSavedKey(), savedEmbeddingKey,
                    request.provider(), request.baseUrl());
            embeddingEndpoint = new ModelEndpoint(request.provider(), request.baseUrl(), key, request.modelName());
        }

        PythonAiEngineClient.ProbeResult outcome =
                pythonAiEngineClient.probeModel(userId, probeType, chatEndpoint, embeddingEndpoint);
        // 公共接口只允许单类型探测；BOTH 仅用于内部保存流程（DTO 已用 @Pattern 拦截）
        boolean reachable = probeType == ModelProbeType.CHAT
                ? outcome.chatReachable()
                : outcome.embeddingReachable();
        String message = outcome.actualDim() != null
                ? "模型连通性探测通过，实测向量维度：" + outcome.actualDim()
                : "模型连通性探测通过";
        return Result.ok(message, new ModelProbeVO(
                reachable, probeType.name(), request.modelName(), outcome.actualDim(), outcome.latencyMs()));
    }

    /**
     * `API-USER-003`：探测并保存 BYOK 模型配置。
     *
     * <p>顺序严格按 {@code 04} §4.2.3：SSRF 校验 → 事务外调 Python 探测 → 加密 → 短事务只更新组 B。
     * 探测失败时数据库原配置保持完好（任何写操作都发生在探测成功之后）。
     */
    @PutMapping("/model-config")
    public Result<UserConfigVO> saveModelConfig(@Valid @RequestBody ModelConfigUpdateRequest request) {
        Long userId = SecurityContextUtils.requireCurrentUserId();
        UserConfigEntity current = currentEntity();
        java.time.LocalDateTime expectedUpdatedAt = current.getUpdatedAt();
        boolean targetChanged = embeddingTargetChanged(current, request);
        long existingCourses = userCourseCountProvider.countActiveCourses(userId);
        if (targetChanged && existingCourses > 0 && !request.migrateExistingCourses()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "修改默认向量模型会影响已有 " + existingCourses
                            + " 门课程；请确认迁移已有课程后再保存。");
        }

        baseUrlSecurityValidator.validate(request.chatBaseUrl());
        baseUrlSecurityValidator.validate(request.embeddingBaseUrl());

        String providedChatKey = normalizeSecret(request.chatApiKey());
        boolean chatKeyFromSaved = providedChatKey == null && current.getChatApiKeyCipher() != null;
        String chatPlainKey;
        if (providedChatKey != null) {
            chatPlainKey = providedChatKey;
        } else if (chatKeyFromSaved) {
            // 未输入新 Key：只有目标（provider/origin/model）完全一致时才允许复用旧 Key
            modelKeyBindingRules.requireSameTarget(
                    current.getChatProvider(), current.getChatBaseUrl(), current.getChatModel(),
                    request.chatProvider(), request.chatBaseUrl(), request.chatModel());
            chatPlainKey = cryptoService.decrypt(current.getChatApiKeyCipher());
        } else {
            chatPlainKey = null;
        }
        requireKeyOrWhitelisted(chatPlainKey, request.chatProvider(), request.chatBaseUrl(), "对话模型");

        String providedEmbeddingKey = normalizeSecret(request.embeddingApiKey());
        String embeddingPlainKey;
        if (request.reuseChatApiKey()) {
            if (providedChatKey == null && current.getChatApiKeyCipher() != null) {
                // 复用“已保存的对话 Key”发往 Embedding：provider + origin 必须与对话 Key 的绑定一致；
                // 模型名允许不同（这是产品明确允许的唯一复用例外）。
                modelKeyBindingRules.requireSameProviderAndOrigin(
                        current.getChatProvider(), current.getChatBaseUrl(),
                        request.defaultEmbeddingProvider(), request.embeddingBaseUrl());
            }
            embeddingPlainKey = chatPlainKey;
        } else if (providedEmbeddingKey != null) {
            embeddingPlainKey = providedEmbeddingKey;
        } else if (current.getEmbeddingApiKeyCipher() != null) {
            modelKeyBindingRules.requireSameTarget(
                    current.getDefaultEmbeddingProvider(), current.getEmbeddingBaseUrl(),
                    current.getDefaultEmbeddingModel(),
                    request.defaultEmbeddingProvider(), request.embeddingBaseUrl(),
                    request.defaultEmbeddingModel());
            embeddingPlainKey = cryptoService.decrypt(current.getEmbeddingApiKeyCipher());
        } else {
            embeddingPlainKey = null;
        }
        requireKeyOrWhitelisted(
                embeddingPlainKey, request.defaultEmbeddingProvider(), request.embeddingBaseUrl(), "向量模型");

        // 加密前的字节级列宽校验：必须在探测/写库之前，避免多字节 Key 先触发上游 500 或 DB 长度异常
        if (providedChatKey != null) {
            cryptoService.requireStorable(providedChatKey);
        }
        if (providedEmbeddingKey != null && !request.reuseChatApiKey()) {
            cryptoService.requireStorable(providedEmbeddingKey);
        }

        PythonAiEngineClient.ProbeResult outcome = pythonAiEngineClient.probeModel(
                userId,
                ModelProbeType.BOTH,
                new ModelEndpoint(request.chatProvider(), request.chatBaseUrl(), chatPlainKey, request.chatModel()),
                new ModelEndpoint(request.defaultEmbeddingProvider(), request.embeddingBaseUrl(),
                        embeddingPlainKey, request.defaultEmbeddingModel()));
        if (!outcome.chatReachable() || !outcome.embeddingReachable()
                || outcome.actualDim() == null || outcome.actualDim() <= 0) {
            // 探测不通过时绝不落库：用户的旧配置必须原样保留
            throw new BusinessException(ErrorCode.UPSTREAM_MODEL_ERROR, PROBE_UPSTREAM_FAILED);
        }

        // null 表示"保留原密文"；只有本次真正拿到明文才重新加密（每次生成独立 IV）
        String chatCipher = providedChatKey != null ? cryptoService.encrypt(providedChatKey) : null;
        String embeddingCipher;
        if (request.reuseChatApiKey()) {
            embeddingCipher = chatPlainKey != null ? cryptoService.encrypt(chatPlainKey) : null;
        } else {
            embeddingCipher = providedEmbeddingKey != null ? cryptoService.encrypt(providedEmbeddingKey) : null;
        }

        UserConfigEntity updated = modelConfigSaveService.saveAndMaybeMigrate(userId,
                new UserConfigService.ByokModelUpdate(
                        request.chatProvider(), request.chatBaseUrl(), request.chatModel(), chatCipher,
                        request.defaultEmbeddingProvider(), request.embeddingBaseUrl(),
                        request.defaultEmbeddingModel(), outcome.actualDim(), embeddingCipher),
                request.migrateExistingCourses(),
                request.defaultEmbeddingModel(), outcome.actualDim(),
                expectedUpdatedAt);
        String message = targetChanged && request.migrateExistingCourses()
                ? "模型配置已保存，已有课程已提交重建" : "模型配置已保存";
        return Result.ok(message,
                vo(updated, userCourseCountProvider.countActiveCourses(userId)));
    }

    /** Embedding 目标（provider + 规范化到字符串级的 Base URL + 模型名）是否发生变化。 */
    private static boolean embeddingTargetChanged(UserConfigEntity current,
                                                  ModelConfigUpdateRequest request) {
        return !equalsTrimmed(current.getDefaultEmbeddingProvider(),
                        request.defaultEmbeddingProvider())
                || !equalsTrimmed(current.getEmbeddingBaseUrl(), request.embeddingBaseUrl())
                || !equalsTrimmed(current.getDefaultEmbeddingModel(),
                        request.defaultEmbeddingModel());
    }

    private static boolean equalsTrimmed(String left, String right) {
        return left == null ? right == null : left.trim().equals(right == null ? null : right.trim());
    }

    /** `API-USER-004`：更新个人偏好与 IM 绑定（组 B 偏好列）。 */
    @PutMapping("/preferences")
    public Result<UserConfigVO> updatePreferences(@Valid @RequestBody PreferencesRequest request) {
        Long userId = SecurityContextUtils.requireCurrentUserId();
        requireValidTimeZone(request.timezone());
        LocalTime dndStart = parseTime(request.dndStartTime(), "dnd_start_time");
        LocalTime dndEnd = parseTime(request.dndEndTime(), "dnd_end_time");

        String platform = normalizeSecret(request.boundImPlatform());
        String imUid = normalizeSecret(request.boundImUid());
        if ((platform == null) != (imUid == null)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "bound_im_platform 与 bound_im_uid 必须同时为空或同时非空。");
        }
        if (platform != null && !IM_PLATFORMS.contains(platform.toUpperCase(Locale.ROOT))) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "bound_im_platform 取值必须为 QQ_ONEBOT 或 WECOM。");
        }

        UserConfigEntity updated = userConfigService.updatePreferences(
                userId, request.timezone(), dndStart, dndEnd,
                platform == null ? null : platform.toUpperCase(Locale.ROOT), imUid);
        return Result.ok("偏好设置已更新", vo(updated, null));
    }

    /** `API-USER-006`：二次确认注销当前账号，立即封禁并异步物理擦除全部个人数据。 */
    @PostMapping("/account/deactivate")
    public Result<Void> deactivateAccount(
            @Valid @RequestBody DeactivateAccountRequest request) {
        Long userId = SecurityContextUtils.requireCurrentUserId();
        userDeactivationService.deactivate(userId, request.password(), request.confirmText());
        return Result.ok("账号已注销，所有关联个人数据正在执行物理擦除", null);
    }

    /** `API-USER-005`：销毁已保存的 API Key（幂等，重复调用同样成功）。 */
    @DeleteMapping("/model-config/keys")
    public Result<UserConfigVO> clearApiKeys(
            @RequestParam(value = "target", required = false, defaultValue = "ALL") String target) {
        Long userId = SecurityContextUtils.requireCurrentUserId();
        boolean clearChat;
        boolean clearEmbedding;
        switch (target == null ? "ALL" : target.trim().toUpperCase(Locale.ROOT)) {
            case "ALL" -> {
                clearChat = true;
                clearEmbedding = true;
            }
            case "CHAT" -> {
                clearChat = true;
                clearEmbedding = false;
            }
            case "EMBEDDING" -> {
                clearChat = false;
                clearEmbedding = true;
            }
            default -> throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "target 取值必须为 ALL、CHAT 或 EMBEDDING。");
        }
        UserConfigEntity updated = userConfigService.clearApiKeys(userId, clearChat, clearEmbedding);
        return Result.ok("已销毁保存的 API Key", vo(updated, null));
    }

    private UserConfigEntity currentEntity() {
        Long userId = SecurityContextUtils.requireCurrentUserId();
        return userConfigService.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_UNAUTHORIZED, "登录状态已失效，请重新登录。"));
    }

    /**
     * 读取并解密已保存 Key；**先验证目标绑定**，绑定不一致时绝不碰密文。
     */
    private String readSavedKey(String cipherText,
                                String savedProvider, String savedBaseUrl, String savedModel,
                                String submittedProvider, String submittedBaseUrl, String submittedModel,
                                String label) {
        if (cipherText == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    label + "没有已保存的 API Key，请重新输入。");
        }
        modelKeyBindingRules.requireSameTarget(savedProvider, savedBaseUrl, savedModel,
                submittedProvider, submittedBaseUrl, submittedModel);
        return cryptoService.decrypt(cipherText);
    }

    /**
     * 探测阶段的 Key 解析：候选明文优先；否则按 `use_saved_key` 取已保存 Key；
     * 免密只允许**管理员白名单内的本地 Ollama**（与保存路径同一规则）。
     */
    private String resolveProbeKey(String providedKey, boolean useSavedKey, String savedKey,
                                   String provider, String baseUrl) {
        if (providedKey != null) {
            return providedKey;
        }
        if (useSavedKey && savedKey != null) {
            return savedKey;
        }
        if (modelConfigRules.isWhitelistedLocalOllama(provider, baseUrl)) {
            return null;
        }
        throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                "请提供 API Key；免密仅限管理员白名单内的本地 Ollama。");
    }

    /** 保存阶段的有效性规则（{@code 03} §4.2）：非免密供应商必须有 Key，白名单本地 Ollama 可无 Key。 */
    private void requireKeyOrWhitelisted(String plainKey, String provider, String baseUrl, String label) {
        if (plainKey != null) {
            return;
        }
        if (modelConfigRules.isWhitelistedLocalOllama(provider, baseUrl)) {
            return;
        }
        throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                label + " API Key 不能为空（免密仅限管理员白名单内的本地 Ollama）。");
    }

    private String decryptIfPresent(String cipherText) {
        return cipherText == null ? null : cryptoService.decrypt(cipherText);
    }

    private static String normalizeSecret(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static void requireValidTimeZone(String timezone) {
        try {
            ZoneId.of(timezone);
        } catch (DateTimeException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "timezone 必须是合法的 IANA 时区，如 Asia/Shanghai。");
        }
    }

    /** `04` §2.5：纯时刻接受 `HH:mm` 与 `HH:mm:ss` 两种写法。 */
    private static LocalTime parseTime(String value, String field) {
        String text = value == null ? "" : value.trim();
        try {
            return text.length() == 5
                    ? LocalTime.parse(text, DateTimeFormatter.ofPattern("HH:mm"))
                    : LocalTime.parse(text, DateTimeFormatter.ofPattern("HH:mm:ss"));
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    field + " 格式必须为 HH:mm 或 HH:mm:ss。");
        }
    }

    /** `04` §2.5：纯时刻统一输出 `HH:mm:ss`（`LocalTime#toString` 会省略为 `23:30`，不合契约）。 */
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** `04` §2.5：完整时间戳统一带时区偏移量（`yyyy-MM-ddTHH:mm:ss.SSS+08:00`）。 */
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    static UserConfigVO toVO(UserConfigEntity entity, AesGcmCryptoService cryptoService,
                             ModelConfigRules modelConfigRules) {
        return toVO(entity, cryptoService, modelConfigRules, null, false);
    }

    /**
     * 实体 → VO：只挑选可对外字段，密文与密码哈希永远不出库（{@code 03} §11.2）。
     *
     * @param existingCourseCount 仅 `API-USER-003` 返回；`API-USER-001` 传 null，字段被整体省略
     * @param platformEmbedding   部署方是否统一提供向量模型（前端据此标注「平台默认」）
     */
    static UserConfigVO toVO(UserConfigEntity entity, AesGcmCryptoService cryptoService,
                             ModelConfigRules modelConfigRules, Integer existingCourseCount,
                             boolean platformEmbedding) {
        String chatKeyMasked = masked(entity.getChatApiKeyCipher(), cryptoService);
        String embeddingKeyMasked = masked(entity.getEmbeddingApiKeyCipher(), cryptoService);
        return new UserConfigVO(
                entity.getUserId(),
                entity.getUsername(),
                entity.getChatProvider(),
                entity.getChatBaseUrl(),
                entity.getChatApiKeyCipher() != null,
                chatKeyMasked,
                entity.getChatModel(),
                entity.getDefaultEmbeddingProvider(),
                entity.getEmbeddingBaseUrl(),
                entity.getEmbeddingApiKeyCipher() != null,
                embeddingKeyMasked,
                entity.getDefaultEmbeddingModel(),
                entity.getDefaultEmbeddingDim(),
                entity.getTimezone(),
                formatTime(entity.getDndStartTime()),
                formatTime(entity.getDndEndTime()),
                entity.getBoundImPlatform(),
                entity.getBoundImUid(),
                formatTimestamp(entity.getUpdatedAt(), entity.getTimezone()),
                modelConfigRules.isChatConfigValid(entity),
                platformEmbedding,
                existingCourseCount);
    }

    /** 解密后只取前 3 位与后 4 位（{@code 04} `API-USER-001`），明文即刻丢弃。 */
    private static String masked(String cipherText, AesGcmCryptoService cryptoService) {
        if (cipherText == null) {
            return null;
        }
        try {
            return AesGcmCryptoService.mask(cryptoService.decrypt(cipherText));
        } catch (BusinessException e) {
            // 旧密文损坏/主密钥更换时不能让整个配置页 500：返回占位掩码，
            // 用户仍可通过“重新保存”或“销毁 Key”恢复（不记录密文或异常细节）。
            log.warn("已保存的 API Key 无法解密，返回占位掩码（用户可重新保存或销毁）");
            return "****";
        }
    }

    private static String formatTime(LocalTime time) {
        return time == null ? null : time.format(TIME_FORMAT);
    }

    /** 按用户自己的时区渲染偏移量（`t_user_config.timezone`，默认 Asia/Shanghai）。 */
    private static String formatTimestamp(java.time.LocalDateTime timestamp, String timezone) {
        if (timestamp == null) {
            return null;
        }
        ZoneId zone;
        try {
            zone = timezone == null || timezone.isBlank() ? ZoneId.of("Asia/Shanghai") : ZoneId.of(timezone);
        } catch (DateTimeException e) {
            // 库里的时区值异常不应让整个配置页打不开，退回应用基线时区
            zone = ZoneId.of("Asia/Shanghai");
        }
        return timestamp.atZone(zone).format(TIMESTAMP_FORMAT);
    }
}
