package com.scholarclaw.modules.userconfig.service;

import com.scholarclaw.common.event.UserCleanupCompletedEvent;
import com.scholarclaw.common.security.AccountDeactivationKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 账号注销第二阶段完成度登记（TASK-901）。
 *
 * <p>每个模块完成自身物理清理后把模块名写入 Redis Set
 * `sc:u:{uid}:cleanup:modules`；只有全部参与模块都到齐，`t_user_config` 行才允许物理删除。
 * 该行同时是 GC 发现“注销未完成”的标记，提前删除会让残留数据失去补偿入口。
 *
 * <p>Redis 不可用时返回 `false`，账号保持 `DEACTIVATING`，交给 TASK-902 的 GC 重试；
 * 不得因为登记失败而误删用户行。
 */
@Component
public class UserCleanupRegistry {

    private static final Logger log = LoggerFactory.getLogger(UserCleanupRegistry.class);
    private static final String KEY = "sc:u:%d:cleanup:modules";

    private final StringRedisTemplate redisTemplate;

    public UserCleanupRegistry(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** @return true 表示所有模块都已完成清理 */
    public boolean record(Long userId, UserCleanupCompletedEvent.Module module) {
        String key = KEY.formatted(userId);
        try {
            redisTemplate.opsForSet().add(key, module.name());
            redisTemplate.expire(key, AccountDeactivationKey.TTL);
            Long size = redisTemplate.opsForSet().size(key);
            return size != null && size >= UserCleanupCompletedEvent.Module.values().length;
        } catch (RuntimeException e) {
            log.error("账号注销进度登记失败 userId={} module={} error={}",
                    userId, module, e.getClass().getSimpleName());
            return false;
        }
    }
}
