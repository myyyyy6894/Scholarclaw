package com.scholarclaw.modules.userconfig.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.scholarclaw.common.config.ScTimeZone;
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

    /** 账号是否处于注销清理中（`03` §4.1 `account_status = DEACTIVATING`）。 */
    public static boolean isDeactivating(UserConfigEntity entity) {
        return entity != null && "DEACTIVATING".equals(entity.getAccountStatus());
    }
}
