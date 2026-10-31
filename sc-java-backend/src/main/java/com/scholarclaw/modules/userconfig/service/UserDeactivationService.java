package com.scholarclaw.modules.userconfig.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.scholarclaw.common.config.ScTimeZone;
import com.scholarclaw.common.event.UserDeactivatingEvent;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.common.security.AccountDeactivationKey;
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import com.scholarclaw.modules.userconfig.mapper.UserConfigMapper;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号注销（{@code 04} `API-USER-006`、{@code 06} TASK-901）。
 *
 * <p>第一阶段：校验密码与确认口令 → 写 Redis 注销阻断键（立即封禁所有 Web/IM 请求）→
 * 本地事务把账号置 `DEACTIVATING`、清空密钥与 IM 绑定并发布 {@link UserDeactivatingEvent}。
 *
 * <p>第二阶段由各模块在 `AFTER_COMMIT` 完成自身物理清理并上报；全部模块到齐后
 * {@link #completeCleanup} 物理删除 `t_user_config` 行并清理 Redis 业务键。
 * 中途失败时用户行保留为 `DEACTIVATING`，供 TASK-902 的 GC 重试。
 */
@Service
public class UserDeactivationService {

    private static final Logger log = LoggerFactory.getLogger(UserDeactivationService.class);
    private static final String CONFIRM_TEXT = "CONFIRM_DEACTIVATE";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_DEACTIVATING = "DEACTIVATING";

    private final UserConfigMapper userConfigMapper;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate redisTemplate;
    private final ApplicationEventPublisher eventPublisher;

    public UserDeactivationService(UserConfigMapper userConfigMapper,
                                   PasswordEncoder passwordEncoder,
                                   StringRedisTemplate redisTemplate,
                                   ApplicationEventPublisher eventPublisher) {
        this.userConfigMapper = userConfigMapper;
        this.passwordEncoder = passwordEncoder;
        this.redisTemplate = redisTemplate;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(rollbackFor = Exception.class)
    public void deactivate(Long userId, String password, String confirmText) {
        if (!CONFIRM_TEXT.equals(confirmText)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "确认口令不正确，请输入 CONFIRM_DEACTIVATE。");
        }
        if (password == null || password.isBlank()) {
            throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }
        UserConfigEntity account = userConfigMapper.selectById(userId);
        if (account == null || !passwordEncoder.matches(password, account.getPasswordHash())) {
            throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }
        if (STATUS_DEACTIVATING.equals(account.getAccountStatus())) {
            // 幂等：已进入注销流程，不再重复发布事件或重置进度。
            return;
        }
        blockImmediately(userId);
        int updated = userConfigMapper.update(null, Wrappers.<UserConfigEntity>lambdaUpdate()
                .eq(UserConfigEntity::getUserId, userId)
                .eq(UserConfigEntity::getAccountStatus, STATUS_ACTIVE)
                .set(UserConfigEntity::getAccountStatus, STATUS_DEACTIVATING)
                .set(UserConfigEntity::getChatApiKeyCipher, null)
                .set(UserConfigEntity::getEmbeddingApiKeyCipher, null)
                .set(UserConfigEntity::getBoundImPlatform, null)
                .set(UserConfigEntity::getBoundImUid, null)
                .set(UserConfigEntity::getUpdatedAt, ScTimeZone.now().toLocalDateTime()));
        if (updated != 1) {
            UserConfigEntity current = userConfigMapper.selectById(userId);
            if (current != null && STATUS_DEACTIVATING.equals(current.getAccountStatus())) {
                return;
            }
            throw new BusinessException(ErrorCode.STATE_CONFLICT,
                    "账号状态已变化，请刷新后重试。");
        }
        eventPublisher.publishEvent(new UserDeactivatingEvent(userId));
        log.info("账号注销已启动 userId={}", userId);
    }

    /**
     * 所有模块完成清理后：先清理 Redis `sc:u:{uid}:*`（保留注销阻断键），再物理删除用户行。
     * Redis 清理失败时保留用户行，交给 GC 重试，避免“用户行没了但 Redis 仍有业务键”。
     */
    @Transactional(rollbackFor = Exception.class)
    public void completeCleanup(Long userId) {
        if (!purgeRedisKeys(userId)) {
            log.warn("账号注销：Redis 业务键清理未完成，保留用户行等待 GC userId={}", userId);
            return;
        }
        int deleted = userConfigMapper.delete(Wrappers.<UserConfigEntity>lambdaQuery()
                .eq(UserConfigEntity::getUserId, userId)
                .eq(UserConfigEntity::getAccountStatus, STATUS_DEACTIVATING));
        if (deleted > 0) {
            log.info("账号注销完成：用户行已物理删除 userId={}", userId);
        }
    }

    private void blockImmediately(Long userId) {
        try {
            redisTemplate.opsForValue().set(AccountDeactivationKey.of(userId), "1",
                    AccountDeactivationKey.TTL);
        } catch (RuntimeException e) {
            // 无法立即封禁时不得继续注销，否则旧 JWT 仍可访问已被删除的数据。
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "无法写入注销阻断键，请稍后重试。");
        }
    }

    private boolean purgeRedisKeys(Long userId) {
        String prefix = "sc:u:" + userId + ":";
        String keep = AccountDeactivationKey.of(userId);
        List<String> keys = new ArrayList<>();
        try (Cursor<String> cursor = redisTemplate.scan(ScanOptions.scanOptions()
                .match(prefix + "*").count(200).build())) {
            cursor.forEachRemaining(key -> {
                if (!keep.equals(key)) {
                    keys.add(key);
                }
            });
            if (!keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
            return true;
        } catch (RuntimeException e) {
            log.error("账号注销：Redis 业务键清理失败 userId={} error={}",
                    userId, e.getClass().getSimpleName());
            return false;
        }
    }
}
