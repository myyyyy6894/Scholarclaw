package com.scholarclaw.modules.userconfig.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.scholarclaw.common.config.ScTimeZone;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import com.scholarclaw.modules.userconfig.mapper.UserConfigMapper;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code t_user_config} 的唯一读写入口（{@code 03} §6.0 Single-Writer）。
 *
 * <p>{@code auth} 模块**不持有 Mapper**，注册与登录都必须经由本服务（`auth` 的 package-info 亦如此约定）。
 * 每个方法只碰自己该碰的字段组：
 * <ul>
 *   <li>{@link #createUserAccount} 只写组 A（认证列）与偏好默认值，BYOK 列保持 `NULL`；
 *       唯一例外是部署方配置了平台默认向量模型时，同事务写入组 B 的 Embedding 列
 *       （见 {@link PlatformEmbeddingDefaults}），对话模型列仍为 `NULL`；</li>
 *   <li>组 B 的显式更新方法属 TASK-203（BYOK），本阶段不提供"整行更新"入口。</li>
 * </ul>
 */
@Service
public class UserConfigService {

    /** 默认免打扰窗口（{@code 03} §4.1 `dnd_start_time` / `dnd_end_time` 默认值）。 */
    private static final String DEFAULT_DND_START = "23:30:00";
    private static final String DEFAULT_DND_END = "07:30:00";

    private final UserConfigMapper userConfigMapper;
    private final PlatformEmbeddingDefaults platformEmbeddingDefaults;
    private final ModelConfigRules modelConfigRules;

    public UserConfigService(UserConfigMapper userConfigMapper,
                             PlatformEmbeddingDefaults platformEmbeddingDefaults,
                             ModelConfigRules modelConfigRules) {
        this.userConfigMapper = userConfigMapper;
        this.platformEmbeddingDefaults = platformEmbeddingDefaults;
        this.modelConfigRules = modelConfigRules;
    }

    /**
     * 创建账号（只写组 A；平台默认向量模型启用时附带写入 Embedding 列）。
     *
     * <p>并发同名由数据库唯一索引 `uk_user_username` 兜底，冲突时抛
     * {@link DuplicateKeyException} 交给上层映射为 `409 USER_ALREADY_EXISTS`——
     * 不在应用层做"先查再插"的伪并发保护。
     */
    @Transactional(rollbackFor = Exception.class)
    public UserConfigEntity createUserAccount(String username, String passwordHash, String timezone) {
        UserConfigEntity entity = new UserConfigEntity();
        entity.setUsername(username);
        entity.setPasswordHash(passwordHash);
        entity.setAccountStatus("ACTIVE");
        entity.setKeyVersion("v1");
        entity.setTimezone(timezone);
        entity.setDndStartTime(java.time.LocalTime.parse(DEFAULT_DND_START));
        entity.setDndEndTime(java.time.LocalTime.parse(DEFAULT_DND_END));
        // 部署方自带向量模型时，注册即写入默认 Embedding（含加密后的平台 Key）。
        // 是同一次 INSERT，不存在"账号建好但配置没写进去"的中间态。
        platformEmbeddingDefaults.seed().ifPresent(seed -> {
            entity.setDefaultEmbeddingProvider(seed.provider());
            entity.setEmbeddingBaseUrl(seed.baseUrl());
            entity.setDefaultEmbeddingModel(seed.model());
            entity.setDefaultEmbeddingDim(seed.dim());
            entity.setEmbeddingApiKeyCipher(seed.apiKeyCipher());
        });
        var now = ScTimeZone.now().toLocalDateTime();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        userConfigMapper.insert(entity);
        // 重新读一次：让调用方拿到数据库真实落库值（含自增 user_id），而不是"我以为插进去的值"
        return userConfigMapper.selectById(entity.getUserId());
    }

    /**
     * 登录时补齐平台默认向量模型（存量账号）。
     *
     * <p>注册时写入只覆盖新账号；平台配置启用前注册的用户、以及自己销毁过 Key 的用户，
     * 每次登录检查一次：**只有 Embedding 配置无效时**才写入平台默认值，
     * 用户自己配好的配置一律不覆盖（{@link ModelConfigRules#isEmbeddingConfigValid}）。
     *
     * <p>没有平台默认值时直接返回，保持"用户自行配置"的原有行为。
     */
    @Transactional(rollbackFor = Exception.class)
    public void applyPlatformEmbeddingIfMissing(Long userId) {
        Optional<PlatformEmbeddingDefaults.Seed> seed = platformEmbeddingDefaults.seed();
        if (userId == null || seed.isEmpty()) {
            return;
        }
        UserConfigEntity entity = findById(userId).orElse(null);
        if (entity == null || modelConfigRules.isEmbeddingConfigValid(entity)) {
            return;
        }
        PlatformEmbeddingDefaults.Seed value = seed.get();
        userConfigMapper.update(null, Wrappers.<UserConfigEntity>lambdaUpdate()
                .eq(UserConfigEntity::getUserId, userId)
                .set(UserConfigEntity::getDefaultEmbeddingProvider, value.provider())
                .set(UserConfigEntity::getEmbeddingBaseUrl, value.baseUrl())
                .set(UserConfigEntity::getDefaultEmbeddingModel, value.model())
                .set(UserConfigEntity::getDefaultEmbeddingDim, value.dim())
                .set(UserConfigEntity::getEmbeddingApiKeyCipher, value.apiKeyCipher())
                .set(UserConfigEntity::getUpdatedAt, ScTimeZone.now().toLocalDateTime()));
    }

    /** 按用户名查询（登录与注册查重都命中 `uk_user_username`）。 */
    public Optional<UserConfigEntity> findByUsername(String username) {
        return Optional.ofNullable(userConfigMapper.selectOne(
                Wrappers.<UserConfigEntity>lambdaQuery().eq(UserConfigEntity::getUsername, username)));
    }

    /** 按主键查询（当前用户信息等只读场景）。 */
    public Optional<UserConfigEntity> findById(Long userId) {
        return Optional.ofNullable(userConfigMapper.selectById(userId));
    }

    /**
     * 按外部 IM 绑定账号反查活跃用户（`API-WEBHOOK-001` 白名单映射）。
     *
     * <p>命中唯一索引 {@code uk_user_bound_im (bound_im_platform, bound_im_uid)}；只返回
     * {@code account_status = 'ACTIVE'} 的用户，注销中/停用账号不得触发任何业务。
     */
    public Optional<UserConfigEntity> findActiveByBoundIm(String platform, String imUid) {
        if (platform == null || platform.isBlank() || imUid == null || imUid.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(userConfigMapper.selectOne(
                Wrappers.<UserConfigEntity>lambdaQuery()
                        .eq(UserConfigEntity::getBoundImPlatform, platform.trim())
                        .eq(UserConfigEntity::getBoundImUid, imUid.trim())
                        .eq(UserConfigEntity::getAccountStatus, "ACTIVE")));
    }

    /**
     * 锁定当前用户配置行（TASK-506 复核修复）。
     *
     * <p>默认模型保存与建课共用同一把用户级行锁，避免“保存时统计到 0 门课程、探测/保存期间
     * 另一请求用旧配置建课”导致课程绑定失效。调用方必须已开启事务。
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public UserConfigEntity lockForUpdate(Long userId) {
        UserConfigEntity entity = userConfigMapper.selectOne(
                Wrappers.<UserConfigEntity>lambdaQuery()
                        .eq(UserConfigEntity::getUserId, userId)
                        .last("FOR UPDATE"));
        if (entity == null) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED, "登录状态已失效，请重新登录。");
        }
        return entity;
    }

    /**
     * 更新组 B 的模型配置（{@code 04} `API-USER-003`）。
     *
     * <p>**只 set 模型相关列**：`password_hash` / `account_status` / `username` 等组 A 字段
     * 连出现在 SET 子句里的机会都没有（`03` §4.1 专项约束 1）。Key 密文传 `null` 表示"保持不变"，
     * 这样用户不重新输入 Key 时不会被清空。
     */
    @Transactional(rollbackFor = Exception.class)
    public UserConfigEntity updateByokModelConfig(Long userId, ByokModelUpdate update) {
        var wrapper = Wrappers.<UserConfigEntity>lambdaUpdate()
                .eq(UserConfigEntity::getUserId, userId)
                .eq(UserConfigEntity::getAccountStatus, "ACTIVE")
                .set(UserConfigEntity::getChatProvider, update.chatProvider())
                .set(UserConfigEntity::getChatBaseUrl, update.chatBaseUrl())
                .set(UserConfigEntity::getChatModel, update.chatModel())
                .set(UserConfigEntity::getDefaultEmbeddingProvider, update.embeddingProvider())
                .set(UserConfigEntity::getEmbeddingBaseUrl, update.embeddingBaseUrl())
                .set(UserConfigEntity::getDefaultEmbeddingModel, update.embeddingModel())
                .set(UserConfigEntity::getDefaultEmbeddingDim, update.embeddingDim())
                .set(UserConfigEntity::getKeyVersion, "v1")
                .set(UserConfigEntity::getUpdatedAt, ScTimeZone.now().toLocalDateTime())
                .set(update.chatApiKeyCipher() != null, UserConfigEntity::getChatApiKeyCipher, update.chatApiKeyCipher())
                .set(update.embeddingApiKeyCipher() != null,
                        UserConfigEntity::getEmbeddingApiKeyCipher, update.embeddingApiKeyCipher());
        requireUpdated(userConfigMapper.update(null, wrapper));
        return findById(userId).orElseThrow(() -> new BusinessException(
                ErrorCode.AUTH_UNAUTHORIZED, "登录状态已失效，请重新登录。"));
    }

    /** 更新组 B 的偏好列（时区/免打扰/IM 绑定），同样不碰组 A。 */
    @Transactional(rollbackFor = Exception.class)
    public UserConfigEntity updatePreferences(Long userId, String timezone, java.time.LocalTime dndStart,
                                              java.time.LocalTime dndEnd, String imPlatform, String imUid) {
        var wrapper = Wrappers.<UserConfigEntity>lambdaUpdate()
                .eq(UserConfigEntity::getUserId, userId)
                .eq(UserConfigEntity::getAccountStatus, "ACTIVE")
                .set(UserConfigEntity::getTimezone, timezone)
                .set(UserConfigEntity::getDndStartTime, dndStart)
                .set(UserConfigEntity::getDndEndTime, dndEnd)
                .set(UserConfigEntity::getBoundImPlatform, imPlatform)
                .set(UserConfigEntity::getBoundImUid, imUid)
                .set(UserConfigEntity::getUpdatedAt, ScTimeZone.now().toLocalDateTime());
        try {
            requireUpdated(userConfigMapper.update(null, wrapper));
        } catch (DuplicateKeyException e) {
            // uk_user_bound_im 拦截：该 IM 账号已被其他用户绑定（04 §4.2.4 业务规则 2）
            throw new BusinessException(ErrorCode.IM_ACCOUNT_ALREADY_BOUND);
        }
        return findById(userId).orElseThrow(() -> new BusinessException(
                ErrorCode.AUTH_UNAUTHORIZED, "登录状态已失效，请重新登录。"));
    }

    /** 销毁已保存的 API Key（{@code 04} `API-USER-005`）：按目标把密文列置 `NULL`，幂等。 */
    @Transactional(rollbackFor = Exception.class)
    public UserConfigEntity clearApiKeys(Long userId, boolean clearChat, boolean clearEmbedding) {
        var wrapper = Wrappers.<UserConfigEntity>lambdaUpdate()
                .eq(UserConfigEntity::getUserId, userId)
                .eq(UserConfigEntity::getAccountStatus, "ACTIVE")
                .set(UserConfigEntity::getUpdatedAt, ScTimeZone.now().toLocalDateTime());
        if (clearChat) {
            wrapper.set(UserConfigEntity::getChatApiKeyCipher, null);
        }
        if (clearEmbedding) {
            wrapper.set(UserConfigEntity::getEmbeddingApiKeyCipher, null);
        }
        requireUpdated(userConfigMapper.update(null, wrapper));
        return findById(userId).orElseThrow(() -> new BusinessException(
                ErrorCode.AUTH_UNAUTHORIZED, "登录状态已失效，请重新登录。"));
    }

    private static void requireUpdated(int affectedRows) {
        if (affectedRows != 1) {
            // 账号不存在或已注销：不能静默成功，否则调用方会以为"改成功了"
            throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED, "登录状态已失效，请重新登录。");
        }
    }

    /** 组 B 模型配置的更新载荷；密文为 `null` 表示保留原值。 */
    public record ByokModelUpdate(String chatProvider, String chatBaseUrl, String chatModel,
                                  String chatApiKeyCipher, String embeddingProvider, String embeddingBaseUrl,
                                  String embeddingModel, Integer embeddingDim, String embeddingApiKeyCipher) {
    }

    // ------------------------------------------------------------------
    // QQ 官方机器人（用户自带 Bot）：凭据、状态与 openid 绑定
    // ------------------------------------------------------------------

    /**
     * 保存机器人凭据（AppID + AppSecret 密文）。
     *
     * <p>与模型 Key 同一组 B 字段纪律：只写这一组列，不碰认证列；密文由调用方用
     * {@code AesGcmCryptoService} 生成，本方法不接触明文。
     */
    @Transactional(rollbackFor = Exception.class)
    public UserConfigEntity saveQqBotConfig(Long userId, String appId, String secretCipher) {
        var wrapper = Wrappers.<UserConfigEntity>lambdaUpdate()
                .eq(UserConfigEntity::getUserId, userId)
                .eq(UserConfigEntity::getAccountStatus, "ACTIVE")
                .set(UserConfigEntity::getQqbotAppId, appId)
                .set(UserConfigEntity::getQqbotSecretCipher, secretCipher)
                .set(UserConfigEntity::getQqbotStatus, "DISCONNECTED")
                .set(UserConfigEntity::getQqbotLastError, null)
                .set(UserConfigEntity::getUpdatedAt, ScTimeZone.now().toLocalDateTime());
        requireUpdated(userConfigMapper.update(null, wrapper));
        return findById(userId).orElseThrow(() -> new BusinessException(
                ErrorCode.AUTH_UNAUTHORIZED, "登录状态已失效，请重新登录。"));
    }

    /** 断开并清除机器人凭据（保留 openid 之外的绑定信息也一并清空）。 */
    @Transactional(rollbackFor = Exception.class)
    public UserConfigEntity clearQqBotConfig(Long userId) {
        var wrapper = Wrappers.<UserConfigEntity>lambdaUpdate()
                .eq(UserConfigEntity::getUserId, userId)
                .eq(UserConfigEntity::getAccountStatus, "ACTIVE")
                .set(UserConfigEntity::getQqbotAppId, null)
                .set(UserConfigEntity::getQqbotSecretCipher, null)
                .set(UserConfigEntity::getQqbotOpenid, null)
                .set(UserConfigEntity::getQqbotStatus, "DISCONNECTED")
                .set(UserConfigEntity::getQqbotLastError, null)
                .set(UserConfigEntity::getQqbotConnectedAt, null)
                .set(UserConfigEntity::getUpdatedAt, ScTimeZone.now().toLocalDateTime());
        requireUpdated(userConfigMapper.update(null, wrapper));
        return findById(userId).orElseThrow(() -> new BusinessException(
                ErrorCode.AUTH_UNAUTHORIZED, "登录状态已失效，请重新登录。"));
    }

    /** 更新连接状态；`ONLINE` 时同时写连接时间。失败原因由调用方脱敏后传入。 */
    @Transactional(rollbackFor = Exception.class)
    public void updateQqBotStatus(Long userId, String status, String lastError) {
        var wrapper = Wrappers.<UserConfigEntity>lambdaUpdate()
                .eq(UserConfigEntity::getUserId, userId)
                .set(UserConfigEntity::getQqbotStatus, status)
                .set(UserConfigEntity::getQqbotLastError, lastError)
                .set(UserConfigEntity::getUpdatedAt, ScTimeZone.now().toLocalDateTime());
        if ("ONLINE".equals(status)) {
            wrapper.set(UserConfigEntity::getQqbotConnectedAt, ScTimeZone.now().toLocalDateTime());
        }
        userConfigMapper.update(null, wrapper);
    }

    /** 记录用户首次私聊的 openid（后续只接受同一 openid 的消息）。 */
    @Transactional(rollbackFor = Exception.class)
    public void bindQqBotOpenid(Long userId, String openid) {
        userConfigMapper.update(null, Wrappers.<UserConfigEntity>lambdaUpdate()
                .eq(UserConfigEntity::getUserId, userId)
                .set(UserConfigEntity::getQqbotOpenid, openid)
                .set(UserConfigEntity::getUpdatedAt, ScTimeZone.now().toLocalDateTime()));
    }

    /** 启动时恢复连接：所有配置了机器人的账号。 */
    public java.util.List<UserConfigEntity> findQqBotConfigured() {
        return userConfigMapper.selectList(Wrappers.<UserConfigEntity>lambdaQuery()
                .isNotNull(UserConfigEntity::getQqbotAppId)
                .isNotNull(UserConfigEntity::getQqbotSecretCipher));
    }

    /** AppID 唯一性检查：一个机器人只能归属一个账号。 */
    public boolean isQqBotAppIdTakenByOther(Long userId, String appId) {
        UserConfigEntity entity = userConfigMapper.selectOne(Wrappers.<UserConfigEntity>lambdaQuery()
                .eq(UserConfigEntity::getQqbotAppId, appId)
                .last("LIMIT 1"));
        return entity != null && !entity.getUserId().equals(userId);
    }

    /** GC：跨用户扫描滞留超过阈值的 `DEACTIVATING` 账号。 */
    public java.util.List<Long> findStaleDeactivating(java.time.LocalDateTime before, int limit) {
        return userConfigMapper.selectList(Wrappers.<UserConfigEntity>lambdaQuery()
                        .eq(UserConfigEntity::getAccountStatus, "DEACTIVATING")
                        .lt(UserConfigEntity::getUpdatedAt, before)
                        .orderByAsc(UserConfigEntity::getUpdatedAt)
                        .select(UserConfigEntity::getUserId)
                        .last("LIMIT " + Math.max(1, limit)))
                .stream()
                .map(UserConfigEntity::getUserId)
                .toList();
    }

    /** 账号是否处于注销清理中（`03` §4.1 `account_status = DEACTIVATING`）。 */
    public static boolean isDeactivating(UserConfigEntity entity) {
        return entity != null && "DEACTIVATING".equals(entity.getAccountStatus());
    }

    /**
     * JWT 过滤器的数据库兜底：用户行不存在（已完成注销）或处于 `DEACTIVATING` 都视为不可用。
     *
     * <p>只在 Redis 注销键未命中且无活跃负缓存时调用，避免每个请求都查库。
     */
    public boolean isAccountDeactivatedOrMissing(Long userId) {
        if (userId == null) {
            return true;
        }
        UserConfigEntity entity = userConfigMapper.selectById(userId);
        return entity == null || isDeactivating(entity);
    }
}
