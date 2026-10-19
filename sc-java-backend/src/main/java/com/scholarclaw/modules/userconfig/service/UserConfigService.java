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
 *   <li>{@link #createUserAccount} 只写组 A（认证列）与偏好默认值，BYOK 列保持 `NULL`；</li>
 *   <li>组 B 的显式更新方法属 TASK-203（BYOK），本阶段不提供"整行更新"入口。</li>
 * </ul>
 */
@Service
public class UserConfigService {

    /** 默认免打扰窗口（{@code 03} §4.1 `dnd_start_time` / `dnd_end_time` 默认值）。 */
    private static final String DEFAULT_DND_START = "23:30:00";
    private static final String DEFAULT_DND_END = "07:30:00";

    private final UserConfigMapper userConfigMapper;

    public UserConfigService(UserConfigMapper userConfigMapper) {
        this.userConfigMapper = userConfigMapper;
    }

    /**
     * 创建账号（只写组 A）。
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
        var now = ScTimeZone.now().toLocalDateTime();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        userConfigMapper.insert(entity);
        // 重新读一次：让调用方拿到数据库真实落库值（含自增 user_id），而不是"我以为插进去的值"
        return userConfigMapper.selectById(entity.getUserId());
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

    /** 账号是否处于注销清理中（`03` §4.1 `account_status = DEACTIVATING`）。 */
    public static boolean isDeactivating(UserConfigEntity entity) {
        return entity != null && "DEACTIVATING".equals(entity.getAccountStatus());
    }
}
