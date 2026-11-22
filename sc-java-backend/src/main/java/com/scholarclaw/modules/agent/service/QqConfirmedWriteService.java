package com.scholarclaw.modules.agent.service;

import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.agent.dto.AgentInboundMessage;
import com.scholarclaw.modules.ddl.dto.DdlTaskVO;
import com.scholarclaw.modules.ddl.service.DdlTaskService;
import com.scholarclaw.modules.ddl.service.DdlTaskService.AssistantTaskChange;
import com.scholarclaw.modules.schedule.service.ScheduleReminderService;
import com.scholarclaw.modules.schedule.service.ScheduleReminderService.PreferenceView;
import com.scholarclaw.modules.schedule.service.ScheduleReminderService.PreferenceWrite;
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Holds the binding row lock across a confirmed mutation. Domain services still own their writes. */
@Service
public class QqConfirmedWriteService {
    private final UserConfigService users;
    private final DdlTaskService tasks;
    private final ScheduleReminderService reminders;

    public QqConfirmedWriteService(UserConfigService users, DdlTaskService tasks, ScheduleReminderService reminders) {
        this.users = users;
        this.tasks = tasks;
        this.reminders = reminders;
    }

    static void requireBinding(AgentInboundMessage message, UserConfigEntity config) {
        var session = message.qqSession();
        if (session == null || session.appId() == null || session.bindingVersion() == null || session.openid() == null
                || !"QQ_OFFICIAL".equals(message.sourcePlatform()) || config == null
                || !"ACTIVE".equals(config.getAccountStatus()) || !Integer.valueOf(1).equals(config.getQqbotBindingVerified())
                || !Objects.equals(session.appId(), config.getQqbotAppId())
                || !Objects.equals(session.bindingVersion(), config.getQqbotBindingVersion())
                || !Objects.equals(session.openid(), config.getQqbotOpenid())) {
            throw new BusinessException(ErrorCode.AUTH_FORBIDDEN, "QQ 私聊绑定已变化，请重新验证后操作。");
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PreferenceView reminder(AgentInboundMessage message, boolean enabled, long version) {
        requireBinding(message, users.lockForUpdate(message.userId()));
        return reminders.setPreference(message.userId(), new PreferenceWrite(enabled, version));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DdlTaskVO task(AgentInboundMessage message, long id, OffsetDateTime version, AssistantTaskChange change,
                          LocalDate date, LocalTime time, String timezone) {
        var user = users.lockForUpdate(message.userId());
        requireBinding(message, user);
        if (timezone != null && !timezone.equals(user.getTimezone())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "你的时区已变化，请重新确认截止时间。");
        }
        return tasks.applyAssistantChange(message.userId(), id, version, change, date, time);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public <T> T withVerifiedBinding(AgentInboundMessage message, Supplier<T> action) {
        requireBinding(message, users.lockForUpdate(message.userId()));
        return action.get();
    }
}
