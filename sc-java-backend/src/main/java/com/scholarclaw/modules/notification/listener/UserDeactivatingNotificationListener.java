package com.scholarclaw.modules.notification.listener;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.scholarclaw.common.event.UserCleanupCompletedEvent;
import com.scholarclaw.common.event.UserDeactivatingEvent;
import com.scholarclaw.modules.notification.entity.NotificationLogEntity;
import com.scholarclaw.modules.notification.mapper.NotificationLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 账号注销第二阶段：物理删除该用户的站内通知（{@code 04} `API-USER-006`、TASK-901）。
 *
 * <p>通知是用户私有数据，注销完成后不得保留；出站调度器在账号绑定被清空后不会再推送。
 */
@Component
public class UserDeactivatingNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(
            UserDeactivatingNotificationListener.class);

    private final NotificationLogMapper notificationLogMapper;
    private final ApplicationEventPublisher eventPublisher;

    public UserDeactivatingNotificationListener(NotificationLogMapper notificationLogMapper,
                                                ApplicationEventPublisher eventPublisher) {
        this.notificationLogMapper = notificationLogMapper;
        this.eventPublisher = eventPublisher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void purgeNotifications(UserDeactivatingEvent event) {
        try {
            int deleted = notificationLogMapper.delete(
                    Wrappers.<NotificationLogEntity>lambdaQuery()
                            .eq(NotificationLogEntity::getUserId, event.userId()));
            log.info("账号注销：通知清理完成 userId={} rows={}", event.userId(), deleted);
        } catch (RuntimeException e) {
            log.error("账号注销：通知清理失败 userId={} error={}",
                    event.userId(), e.getClass().getSimpleName());
            return;
        }
        eventPublisher.publishEvent(new UserCleanupCompletedEvent(event.userId(),
                UserCleanupCompletedEvent.Module.NOTIFICATION));
    }
}
