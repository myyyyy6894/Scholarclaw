package com.scholarclaw.modules.ddl.service;

import com.scholarclaw.modules.ddl.entity.DdlTaskEntity;
import com.scholarclaw.modules.ddl.mapper.DdlTaskMapper;
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * DDL 定时提醒扫描（TASK-801、{@code 01} {@code FR-DDL-003}）。
 *
 * <p>每轮扫描两类候选：
 * <ol>
 *   <li>{@code ACTIVE} 且未来 24 小时内到期（或已过期）的任务：T-24h / T-6h / 逾期；</li>
 *   <li>{@code PENDING_TIME} / {@code PENDING_CONFIRM} 且 {@code follow_up_at <= now}
 *       且 {@code follow_up_count < 2} 的任务：温和跟进。</li>
 * </ol>
 *
 * <p><b>分页：</b>两类候选都按 {@code (时间, task_id)} keyset 分页遍历到取空，绝不只扫第一页。
 * 已发 T-24h、尚未到 T-6h 的任务会持续命中条件；若只取 {@code LIMIT batch-size} 的第一页，
 * 候选数超过批大小后排在后面的任务会长期收不到提醒。
 *
 * <p>免打扰窗口、时区换算与提醒位竞态都下沉到 {@link DdlReminderPolicy} 与
 * {@link DdlReminderClaimer}：本类只负责读取用户偏好、隔离单任务失败并汇总结果。
 * 单条任务失败不会中断整轮扫描；失败原因以 {@code ERROR} 日志输出，任务保持未领取状态，
 * 下一分钟自动重试。
 */
@Service
public class DdlReminderService {

    private static final Logger log = LoggerFactory.getLogger(DdlReminderService.class);

    private final DdlTaskMapper ddlTaskMapper;
    private final DdlReminderClaimer claimer;
    private final DdlReminderPolicy policy;
    private final UserConfigService userConfigService;
    private final DdlTimeRuleValidator timeRuleValidator;
    private final int batchSize;

    public DdlReminderService(DdlTaskMapper ddlTaskMapper,
                              DdlReminderClaimer claimer,
                              DdlReminderPolicy policy,
                              UserConfigService userConfigService,
                              DdlTimeRuleValidator timeRuleValidator,
                              @Value("${sc.ddl.reminder.batch-size:200}") int batchSize) {
        this.ddlTaskMapper = ddlTaskMapper;
        this.claimer = claimer;
        this.policy = policy;
        this.userConfigService = userConfigService;
        this.timeRuleValidator = timeRuleValidator;
        this.batchSize = Math.max(1, batchSize);
    }

    /**
     * 执行一次扫描并领取到期提醒。
     *
     * @param now     应用时区当前时间
     * @param traceId 本轮定时任务追踪号（写入通知记录与日志）
     */
    public ReminderScanResult scan(LocalDateTime now, String traceId) {
        List<DdlTaskEntity> candidates = new ArrayList<>();
        candidates.addAll(loadActiveCandidates(now, now.plusHours(DdlReminderPolicy.T24_HOURS)));
        candidates.addAll(loadFollowUpCandidates(now));

        int claimed = 0;
        int unclaimed = 0;
        int failed = 0;
        List<DdlReminderDispatch> dispatches = new ArrayList<>();
        Map<Long, Optional<UserConfigEntity>> configCache = new HashMap<>();
        Set<Long> warnedUsers = new HashSet<>();

        for (DdlTaskEntity task : candidates) {
            Optional<UserConfigEntity> configOpt;
            try {
                configOpt = configCache.computeIfAbsent(task.getUserId(), userConfigService::findById);
            } catch (RuntimeException e) {
                failed++;
                log.error("读取用户配置失败，跳过提醒 taskId={} userId={} error={}",
                        task.getTaskId(), task.getUserId(), e.getClass().getSimpleName(), e);
                continue;
            }
            UserConfigEntity config = configOpt.orElse(null);
            if (config == null || UserConfigService.isDeactivating(config)) {
                // 用户已注销或数据不一致：不发送提醒；账号注销清理会把这些任务置为 CANCELED。
                if (warnedUsers.add(task.getUserId())) {
                    log.warn("跳过缺失/注销中用户的提醒 userId={} taskId={}",
                            task.getUserId(), task.getTaskId());
                }
                continue;
            }

            String timezoneName = resolveTimezone(config, task);
            ZoneId userZone;
            try {
                userZone = timeRuleValidator.resolveUserZone(timezoneName);
            } catch (RuntimeException e) {
                failed++;
                log.error("用户时区非法，跳过提醒 taskId={} userId={} timezone={}",
                        task.getTaskId(), task.getUserId(), timezoneName, e);
                continue;
            }

            try {
                List<DdlReminderDispatch> claimedDispatches = claimer.claim(
                        task, now, userZone,
                        policy.dndStartOrDefault(config.getDndStartTime()),
                        policy.dndEndOrDefault(config.getDndEndTime()),
                        traceId);
                if (claimedDispatches.isEmpty()) {
                    unclaimed++;
                    continue;
                }
                for (DdlReminderDispatch dispatch : claimedDispatches) {
                    log.info("DDL 提醒已领取 taskId={} userId={} type={} dueAt={} followUpCount={}",
                            dispatch.taskId(), dispatch.userId(), dispatch.type(),
                            dispatch.dueAt(), dispatch.followUpCount());
                }
                dispatches.addAll(claimedDispatches);
                claimed += claimedDispatches.size();
            } catch (RuntimeException e) {
                failed++;
                log.error("DDL 提醒领取失败，下一轮重试 taskId={} userId={} error={}",
                        task.getTaskId(), task.getUserId(), e.getClass().getSimpleName(), e);
            }
        }

        return new ReminderScanResult(candidates.size(), claimed, unclaimed, failed,
                List.copyOf(dispatches));
    }

    /**
     * 按 {@code (due_at, task_id)} keyset 遍历完整正式梯度候选集。
     *
     * <p>用“上一页最后一条”推进游标，而不是 OFFSET：提醒领取会修改 {@code reminded_flags}，
     * OFFSET 在结果集变化时会跳行/重行；keyset 只依赖不会被领取修改的 {@code due_at} 与主键。
     */
    private List<DdlTaskEntity> loadActiveCandidates(LocalDateTime now, LocalDateTime horizon) {
        List<DdlTaskEntity> all = new ArrayList<>();
        LocalDateTime afterDueAt = null;
        long afterTaskId = 0L;
        while (true) {
            List<DdlTaskEntity> page = ddlTaskMapper.selectActiveReminderCandidates(
                    now, horizon, afterDueAt, afterTaskId, batchSize);
            if (page == null || page.isEmpty()) {
                return all;
            }
            all.addAll(page);
            DdlTaskEntity last = page.get(page.size() - 1);
            if (last.getDueAt() == null || last.getTaskId() == null
                    || (afterDueAt != null && !last.getDueAt().isAfter(afterDueAt)
                    && last.getTaskId() <= afterTaskId)) {
                // 防御异常 Mapper：游标未前进时立即终止，避免死循环。
                log.error("ACTIVE 提醒候选分页游标未前进，提前终止 afterDueAt={} afterTaskId={}",
                        afterDueAt, afterTaskId);
                return all;
            }
            if (page.size() < batchSize) {
                return all;
            }
            afterDueAt = last.getDueAt();
            afterTaskId = last.getTaskId();
        }
    }

    /** 按 {@code (follow_up_at, task_id)} keyset 遍历完整跟进候选集。 */
    private List<DdlTaskEntity> loadFollowUpCandidates(LocalDateTime now) {
        List<DdlTaskEntity> all = new ArrayList<>();
        LocalDateTime afterFollowUpAt = null;
        long afterTaskId = 0L;
        while (true) {
            List<DdlTaskEntity> page = ddlTaskMapper.selectFollowUpCandidates(
                    now, afterFollowUpAt, afterTaskId, batchSize);
            if (page == null || page.isEmpty()) {
                return all;
            }
            all.addAll(page);
            DdlTaskEntity last = page.get(page.size() - 1);
            if (last.getFollowUpAt() == null || last.getTaskId() == null
                    || (afterFollowUpAt != null && !last.getFollowUpAt().isAfter(afterFollowUpAt)
                    && last.getTaskId() <= afterTaskId)) {
                log.error("跟进提醒候选分页游标未前进，提前终止 afterFollowUpAt={} afterTaskId={}",
                        afterFollowUpAt, afterTaskId);
                return all;
            }
            if (page.size() < batchSize) {
                return all;
            }
            afterFollowUpAt = last.getFollowUpAt();
            afterTaskId = last.getTaskId();
        }
    }

    /**
     * 提醒判定使用的时区：优先用户当前配置；缺失时回退到任务创建时的快照；
     * 两者都缺失时回退应用时区（{@link DdlTimeRuleValidator#normalizeUserTimezone}）。
     */
    private String resolveTimezone(UserConfigEntity config, DdlTaskEntity task) {
        String configured = config.getTimezone();
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        return timeRuleValidator.normalizeUserTimezone(task.getUserTimezone());
    }

    /** 一次扫描的汇总，供调度器记录日志与测试断言。 */
    public record ReminderScanResult(int candidates, int claimed, int unclaimed, int failed,
                                     List<DdlReminderDispatch> dispatches) {
    }
}
