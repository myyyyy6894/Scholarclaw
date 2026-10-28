package com.scholarclaw.modules.ddl.service;

import com.scholarclaw.common.enums.TaskStatus;
import com.scholarclaw.modules.ddl.entity.DdlTaskEntity;
import com.scholarclaw.modules.ddl.mapper.DdlTaskMapper;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 单条 DDL 任务的提醒领取（TASK-801）。
 *
 * <p><b>事务边界：</b>每个候选任务独立一个本地事务；先执行带旧值条件的原子
 * {@code UPDATE}，只有 {@code affected_rows == 1} 才视为本次调度领取成功。这样即便多个
 * 实例同时扫描（Redis 任务锁降级或过期），数据库仍保证同一梯度最多被领取一次。
 *
 * <p><b>TASK-802 接线点：</b>通知落库必须在本方法内部、同一事务内完成（{@code 02} §6.3 步骤 5~7）。
 * 通知插入失败应让事务回滚，使提醒位恢复未领取状态，下一轮自动重试；严禁在事务提交后再发送，
 * 否则会出现“位已置位但站内信永久丢失”的不可恢复缺口。
 */
@Service
public class DdlReminderClaimer {

    private static final Logger log = LoggerFactory.getLogger(DdlReminderClaimer.class);

    private final DdlTaskMapper ddlTaskMapper;
    private final DdlReminderPolicy policy;

    public DdlReminderClaimer(DdlTaskMapper ddlTaskMapper, DdlReminderPolicy policy) {
        this.ddlTaskMapper = ddlTaskMapper;
        this.policy = policy;
    }

    /**
     * 尝试领取一条任务在 {@code now} 时刻应当触发的提醒。
     *
     * @return 成功领取的提醒（可能为空）；空表示已被其他调度领取、任务已变化或尚未到触发时刻
     */
    @Transactional(rollbackFor = Exception.class)
    public List<DdlReminderDispatch> claim(DdlTaskEntity task, LocalDateTime now, ZoneId userZone,
                                           LocalTime dndStart, LocalTime dndEnd, String traceId) {
        if (task == null || task.getTaskId() == null || task.getUserId() == null) {
            return List.of();
        }
        if (TaskStatus.ACTIVE.name().equals(task.getStatus())) {
            return claimActive(task, now, userZone, dndStart, dndEnd, traceId);
        }
        if (TaskStatus.PENDING_TIME.name().equals(task.getStatus())
                || TaskStatus.PENDING_CONFIRM.name().equals(task.getStatus())) {
            return claimFollowUp(task, now, userZone, dndStart, dndEnd, traceId);
        }
        // COMPLETED / CANCELED / 未知状态不产生提醒。
        return List.of();
    }

    private List<DdlReminderDispatch> claimActive(DdlTaskEntity task, LocalDateTime now,
                                                  ZoneId userZone, LocalTime dndStart,
                                                  LocalTime dndEnd, String traceId) {
        LocalDateTime dueAt = task.getDueAt();
        if (dueAt == null) {
            log.warn("ACTIVE 任务缺少 due_at，跳过提醒 taskId={} userId={}",
                    task.getTaskId(), task.getUserId());
            return List.of();
        }
        if (!now.isBefore(dueAt)) {
            int updated = ddlTaskMapper.markOverdueReminded(
                    task.getTaskId(), task.getUserId(), dueAt, now);
            if (updated == 1) {
                return List.of(dispatch(task, DdlReminderType.DDL_OVERDUE, now, userZone,
                        0, traceId));
            }
            return List.of();
        }

        // 更紧急的 T-6h 优先；若同时跨过两个阈值，只发 T-6h 并在 SQL 中静默补记 T-24h 位。
        LocalDateTime t6Trigger = policy.t6TriggerAt(dueAt, userZone,
                policy.dndStartOrDefault(dndStart), policy.dndEndOrDefault(dndEnd));
        if (policy.reminderDue(now, t6Trigger)) {
            int updated = ddlTaskMapper.markT6Reminded(
                    task.getTaskId(), task.getUserId(), dueAt, now);
            if (updated == 1) {
                return List.of(dispatch(task, DdlReminderType.DDL_T6H, now, userZone,
                        0, traceId));
            }
            return List.of();
        }

        LocalDateTime t24Trigger = policy.t24TriggerAt(dueAt);
        if (policy.reminderDue(now, t24Trigger)) {
            int updated = ddlTaskMapper.markT24Reminded(
                    task.getTaskId(), task.getUserId(), dueAt, now);
            if (updated == 1) {
                return List.of(dispatch(task, DdlReminderType.DDL_T24H, now, userZone,
                        0, traceId));
            }
        }
        return List.of();
    }

    private List<DdlReminderDispatch> claimFollowUp(DdlTaskEntity task, LocalDateTime now,
                                                    ZoneId userZone, LocalTime dndStart,
                                                    LocalTime dndEnd, String traceId) {
        LocalDateTime followUpAt = task.getFollowUpAt();
        int count = task.getFollowUpCount() == null ? 0 : task.getFollowUpCount();
        if (followUpAt == null || count >= 2 || now.isBefore(followUpAt)) {
            return List.of();
        }

        LocalDateTime effectiveAt = policy.followUpEffectiveAt(
                followUpAt, now, userZone,
                policy.dndStartOrDefault(dndStart), policy.dndEndOrDefault(dndEnd));
        if (now.isBefore(effectiveAt)) {
            return List.of();
        }

        LocalDateTime nextFollowUpAt = policy.nextFollowUpAt(now);
        int updated = ddlTaskMapper.markFollowUpReminded(
                task.getTaskId(), task.getUserId(), followUpAt, count, nextFollowUpAt, now);
        if (updated != 1) {
            return List.of();
        }
        List<DdlReminderDispatch> dispatches = new ArrayList<>(1);
        dispatches.add(dispatch(task, DdlReminderType.DDL_FOLLOW_UP, now, userZone,
                count + 1, traceId));
        return dispatches;
    }

    private DdlReminderDispatch dispatch(DdlTaskEntity task, DdlReminderType type,
                                         LocalDateTime now, ZoneId userZone, int followUpCount,
                                         String traceId) {
        LocalDateTime dueAt = task.getDueAt();
        LocalDateTime dueAtUserZone = dueAt == null ? null : policy.toUserZone(dueAt, userZone);
        return new DdlReminderDispatch(
                task.getUserId(),
                task.getTaskId(),
                task.getCourseId(),
                type,
                task.getCourseName(),
                task.getTaskTitle(),
                task.getDeliverableFormat(),
                task.getSubmissionChannel(),
                task.getRawTimeText(),
                task.getTimePrecision(),
                task.getTimeInferred() != null && task.getTimeInferred() == 1,
                dueAt,
                dueAtUserZone,
                userZone.getId(),
                followUpCount,
                now,
                traceId);
    }
}
