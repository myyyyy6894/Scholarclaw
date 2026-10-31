package com.scholarclaw.modules.agent.listener;

import com.scholarclaw.common.event.CourseDeletingEvent;
import com.scholarclaw.modules.agent.mapper.ChatMessageMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 课程删除第二阶段：清理该课程的会话历史（{@code 06} TASK-901、{@code 03} §6.7）。
 *
 * <p>`agent` 是 `t_chat_message` 的唯一写入者。会话属于派生数据，课程软删事务提交后直接物理删除；
 * 删除失败不会阻断课程删除，但会记录 ERROR 供 TASK-902 的 GC 重试。
 */
@Component
public class CourseDeletingAgentListener {

    private static final Logger log = LoggerFactory.getLogger(CourseDeletingAgentListener.class);

    private final ChatMessageMapper chatMessageMapper;

    public CourseDeletingAgentListener(ChatMessageMapper chatMessageMapper) {
        this.chatMessageMapper = chatMessageMapper;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void onCourseDeleting(CourseDeletingEvent event) {
        int deleted = chatMessageMapper.deleteByCourse(event.userId(), event.courseId());
        log.info("课程会话清理完成 userId={} courseId={} rows={}",
                event.userId(), event.courseId(), deleted);
    }
}
