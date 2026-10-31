package com.scholarclaw.modules.userconfig.listener;

import com.scholarclaw.common.event.UserCleanupCompletedEvent;
import com.scholarclaw.modules.userconfig.service.UserCleanupRegistry;
import com.scholarclaw.modules.userconfig.service.UserDeactivationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 汇总各模块的注销清理完成事件（TASK-901）。
 *
 * <p>只有全部模块到齐才触发用户行删除与 Redis 业务键清理；
 * 事件可能在原始注销请求线程中同步到达，也可能由后续补偿触发，因此只用 Redis Set 去重。
 */
@Component
public class UserCleanupCompletionListener {

    private static final Logger log = LoggerFactory.getLogger(
            UserCleanupCompletionListener.class);

    private final UserCleanupRegistry registry;
    private final UserDeactivationService deactivationService;

    public UserCleanupCompletionListener(UserCleanupRegistry registry,
                                         UserDeactivationService deactivationService) {
        this.registry = registry;
        this.deactivationService = deactivationService;
    }

    @EventListener
    public void onModuleCompleted(UserCleanupCompletedEvent event) {
        if (registry.record(event.userId(), event.module())) {
            log.info("账号注销：全部模块清理完成，执行最终删除 userId={}", event.userId());
            deactivationService.completeCleanup(event.userId());
        }
    }
}
