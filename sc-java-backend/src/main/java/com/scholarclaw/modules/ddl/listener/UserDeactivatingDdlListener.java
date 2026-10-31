package com.scholarclaw.modules.ddl.listener;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.scholarclaw.common.config.ScTimeZone;
import com.scholarclaw.common.event.UserCleanupCompletedEvent;
import com.scholarclaw.common.event.UserDeactivatingEvent;
import com.scholarclaw.modules.ddl.entity.DdlDecisionReceiptEntity;
import com.scholarclaw.modules.ddl.entity.DdlTaskEntity;
import com.scholarclaw.modules.ddl.mapper.DdlDecisionReceiptMapper;
import com.scholarclaw.modules.ddl.mapper.DdlTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 账号注销时 DDL 任务的冻结与物理清理（{@code 04} `API-USER-006`、{@code 06} TASK-901）。
 *
 * <p>第一阶段与账号状态更新同事务：全部任务置 `CANCELED` 并清空 `follow_up_at`（数据库约束要求
 * 终态不得带跟进时间），立即停止提醒与跟进。第二阶段在事务提交后删除该用户的决策回执与任务行。
 */
@Component
public class UserDeactivatingDdlListener {

    private static final Logger log = LoggerFactory.getLogger(UserDeactivatingDdlListener.class);

    private final DdlTaskMapper taskMapper;
    private final DdlDecisionReceiptMapper receiptMapper;
    private final ApplicationEventPublisher eventPublisher;

    public UserDeactivatingDdlListener(DdlTaskMapper taskMapper,
                                       DdlDecisionReceiptMapper receiptMapper,
                                       ApplicationEventPublisher eventPublisher) {
        this.taskMapper = taskMapper;
        this.receiptMapper = receiptMapper;
        this.eventPublisher = eventPublisher;
    }

    @EventListener
    public void cancelTasks(UserDeactivatingEvent event) {
        int updated = taskMapper.update(null, Wrappers.<DdlTaskEntity>lambdaUpdate()
                .eq(DdlTaskEntity::getUserId, event.userId())
                .ne(DdlTaskEntity::getStatus, "CANCELED")
                .set(DdlTaskEntity::getStatus, "CANCELED")
                .set(DdlTaskEntity::getFollowUpAt, null)
                .set(DdlTaskEntity::getUpdatedAt, ScTimeZone.now().toLocalDateTime()));
        log.info("账号注销：DDL 任务已取消 userId={} rows={}", event.userId(), updated);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void purgeTasks(UserDeactivatingEvent event) {
        try {
            receiptMapper.delete(Wrappers.<DdlDecisionReceiptEntity>lambdaQuery()
                    .eq(DdlDecisionReceiptEntity::getUserId, event.userId()));
            taskMapper.delete(Wrappers.<DdlTaskEntity>lambdaQuery()
                    .eq(DdlTaskEntity::getUserId, event.userId()));
        } catch (RuntimeException e) {
            log.error("账号注销：DDL 数据物理清理失败 userId={} error={}",
                    event.userId(), e.getClass().getSimpleName());
            return;
        }
        eventPublisher.publishEvent(new UserCleanupCompletedEvent(event.userId(),
                UserCleanupCompletedEvent.Module.DDL));
    }
}
