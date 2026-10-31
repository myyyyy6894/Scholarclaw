package com.scholarclaw.modules.agent.listener;

import com.scholarclaw.common.event.UserCleanupCompletedEvent;
import com.scholarclaw.common.event.UserDeactivatingEvent;
import com.scholarclaw.modules.agent.mapper.ChatMessageMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 账号注销第二阶段：物理删除该用户的会话历史（{@code 04} `API-USER-006`、TASK-901）。
 *
 * <p>会话是派生数据，无第一阶段冻结动作；JWT 已在注销开始时被封禁，删除期间不会产生新会话。
 */
@Component
public class UserDeactivatingAgentListener {

    private static final Logger log = LoggerFactory.getLogger(UserDeactivatingAgentListener.class);

    private final ChatMessageMapper chatMessageMapper;
    private final ApplicationEventPublisher eventPublisher;

    public UserDeactivatingAgentListener(ChatMessageMapper chatMessageMapper,
                                         ApplicationEventPublisher eventPublisher) {
        this.chatMessageMapper = chatMessageMapper;
        this.eventPublisher = eventPublisher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void purgeChats(UserDeactivatingEvent event) {
        try {
            int deleted = chatMessageMapper.deleteByUser(event.userId());
            log.info("账号注销：会话清理完成 userId={} rows={}", event.userId(), deleted);
        } catch (RuntimeException e) {
            log.error("账号注销：会话清理失败 userId={} error={}",
                    event.userId(), e.getClass().getSimpleName());
            return;
        }
        eventPublisher.publishEvent(new UserCleanupCompletedEvent(event.userId(),
                UserCleanupCompletedEvent.Module.AGENT));
    }
}
