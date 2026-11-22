package com.scholarclaw.modules.schedule.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.notification.service.NotificationBizType;
import com.scholarclaw.modules.notification.service.NotificationCreateCommand;
import com.scholarclaw.modules.notification.service.NotificationService;
import com.scholarclaw.modules.notification.service.ScheduleReminderOutboundGuard;
import com.scholarclaw.modules.schedule.dto.ScheduleDtos.OccurrenceView;
import com.scholarclaw.modules.schedule.repository.ScheduleReminderRepository;
import com.scholarclaw.modules.schedule.repository.ScheduleReminderRepository.Preference;
import com.scholarclaw.modules.schedule.repository.ScheduleReminderRepository.Reminder;
import com.scholarclaw.modules.schedule.repository.ScheduleRepository;
import com.scholarclaw.modules.schedule.repository.ScheduleRepository.Term;
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Groups the current term's actual opening instants and emits one durable notification per group. */
@Service
public class ScheduleReminderService implements ScheduleReminderOutboundGuard {
    private static final long LEAD_SECONDS = Duration.ofMinutes(20).toSeconds();
    private static final Duration WINDOW = Duration.ofDays(7);
    private static final DateTimeFormatter CLASS_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm XXX");

    private final ScheduleReminderRepository reminders;
    private final ScheduleRepository repository;
    private final ScheduleService schedules;
    private final NotificationService notifications;
    private final UserConfigService users;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final boolean qqEnabled;

    public ScheduleReminderService(ScheduleReminderRepository reminders, ScheduleRepository repository,
            ScheduleService schedules, NotificationService notifications, UserConfigService users,
            ObjectMapper mapper, Clock clock, @Value("${sc.im.push-enabled:false}") boolean qqEnabled) {
        this.reminders = reminders;
        this.repository = repository;
        this.schedules = schedules;
        this.notifications = notifications;
        this.users = users;
        this.mapper = mapper;
        this.clock = clock;
        this.qqEnabled = qqEnabled;
    }

    public record PreferenceView(boolean enabled, long version, String delivery) { }
    public record PreferenceWrite(Boolean enabled, Long version) { }

    @Transactional(readOnly = true)
    public PreferenceView preference(long userId) {
        return view(reminders.preference(userId), users.findById(userId).orElse(null));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PreferenceView setPreference(long userId, PreferenceWrite write) {
        if (write == null || write.enabled() == null || write.version() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请填写提醒开关和配置版本。");
        }
        var user = users.lockForUpdate(userId);
        if (!"ACTIVE".equals(user.getAccountStatus())) throw new BusinessException(ErrorCode.ACCOUNT_DEACTIVATED);
        var old = reminders.preference(userId);
        if (write.version() != old.version()) throw new BusinessException(ErrorCode.STATE_CONFLICT, "提醒偏好已变化，请重新确认。");
        var result = old.enabled() == write.enabled() ? old : reminders.setPreference(userId, write.enabled(), old.version());
        if (!result.enabled() && old.enabled()) cancelFuture(userId, clock.instant().getEpochSecond());
        return view(result, user);
    }

    private PreferenceView view(Preference value, UserConfigEntity user) {
        String channel = "WEB_ONLY_DISABLED";
        if (qqEnabled) {
            channel = user != null && Integer.valueOf(1).equals(user.getQqbotBindingVerified())
                    && user.getQqbotBindingVersion() != null && user.getQqbotAppId() != null
                    && user.getQqbotSecretCipher() != null && user.getQqbotOpenid() != null
                    && !user.getQqbotOpenid().isBlank() ? "WEB_QQ" : "WEB_ONLY_UNVERIFIED";
        }
        return new PreferenceView(value.enabled(), value.version(), channel);
    }

    public List<Long> enabledOwners(long after, int size) { return reminders.enabledOwners(after, size); }

    public void prune() { reminders.prune(clock.instant().minus(WINDOW).getEpochSecond()); }

    /** User row serializes this transaction with other scans and all schedule mutations. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int reconcile(long userId) {
        var user = users.lockForUpdate(userId);
        Instant now = clock.instant();
        if (!"ACTIVE".equals(user.getAccountStatus()) || !reminders.preference(userId).enabled()) return 0;
        var term = repository.activeTerm(userId).orElse(null);
        if (term == null) {
            cancelFuture(userId, now.getEpochSecond());
            return 0;
        }
        ZoneId zone = ZoneId.of(term.timezone());
        LocalDate from = now.atZone(zone).toLocalDate();
        Instant through = now.plus(WINDOW);
        LocalDate to = through.atZone(zone).toLocalDate();
        var current = schedules.occurrences(userId, term.id(), from, to).occurrences();
        var groups = new TreeMap<Long, List<OccurrenceView>>();
        groups.putAll(current.stream().filter(item -> {
            Instant start = OffsetDateTime.parse(item.startAt()).toInstant();
            return start.isAfter(now) && !start.isAfter(through);
        }).collect(Collectors.groupingBy(item -> OffsetDateTime.parse(item.startAt()).toEpochSecond())));
        var stored = new HashMap<Long, Reminder>();
        for (Reminder row : reminders.future(userId, now.getEpochSecond())) stored.put(row.startEpoch(), row);
        int created = 0;
        for (var entry : groups.entrySet()) {
            long start = entry.getKey();
            var group = snapshot(entry.getValue());
            var row = stored.remove(start);
            if (row == null) {
                row = reminders.insert(userId, term.id(), start, term.scheduleVersion(), group.fingerprint(), group.keys());
            } else if (!row.active() || row.termId() != term.id() || row.scheduleVersion() != term.scheduleVersion()) {
                Long keep = row.notificationId();
                if (row.termId() != term.id() || !row.fingerprint().equals(group.fingerprint())) keep = invalidate(row);
                row = reminders.replace(row, term.id(), term.scheduleVersion(), group.fingerprint(), group.keys(), keep);
            }
            Instant during = clock.instant();
            if (row.notificationId() == null && start - LEAD_SECONDS <= during.getEpochSecond() && start > during.getEpochSecond()) {
                long notification = notifications.createInTx(new NotificationCreateCommand(userId, NotificationBizType.SCHEDULE_T20M,
                        row.id(), "课前提醒", format(entry.getValue()), null, start));
                reminders.attachNotification(row, notification);
                created++;
            }
        }
        for (Reminder row : stored.values()) if (row.active()) reminders.cancel(row, invalidate(row));
        return created;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isCurrent(long userId, long reminderId, long noticeId, Instant now) {
        if (!reminders.preference(userId).enabled()) return false;
        Reminder row = reminders.get(userId, reminderId).orElse(null);
        if (row == null || !row.active() || row.startEpoch() <= now.getEpochSecond()
                || !Objects.equals(row.notificationId(), noticeId)) return false;
        Term term = repository.activeTerm(userId).orElse(null);
        if (term == null || term.id() != row.termId()) return false;
        if (term.scheduleVersion() == row.scheduleVersion()) return true;
        LocalDate date = Instant.ofEpochSecond(row.startEpoch()).atZone(ZoneId.of(term.timezone())).toLocalDate();
        var current = schedules.occurrences(userId, term.id(), date, date).occurrences().stream()
                .filter(item -> OffsetDateTime.parse(item.startAt()).toEpochSecond() == row.startEpoch()).toList();
        return !current.isEmpty() && row.fingerprint().equals(snapshot(current).fingerprint());
    }

    private void cancelFuture(long userId, long now) {
        for (Reminder row : reminders.future(userId, now)) if (row.active()) reminders.cancel(row, invalidate(row));
    }

    private Long invalidate(Reminder row) {
        Long notice = row.notificationId();
        return notice != null && notifications.invalidateScheduleOutbound(row.userId(), notice) ? null : notice;
    }

    private GroupSnapshot snapshot(List<OccurrenceView> members) {
        var ordered = members.stream().sorted(Comparator.comparing(OccurrenceView::key)).toList();
        try {
            String keys = mapper.writeValueAsString(ordered.stream().map(OccurrenceView::key).toList());
            byte[] data = mapper.writeValueAsString(ordered).getBytes(StandardCharsets.UTF_8);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
            return new GroupSnapshot(keys, hash);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("课前提醒分组无法序列化", e);
        }
    }

    private static String format(List<OccurrenceView> items) {
        String when = OffsetDateTime.parse(items.getFirst().startAt()).format(CLASS_TIME);
        var lines = items.stream().limit(8).map(item -> item.title() + " · "
                + (item.room() == null ? "教室待定" : item.room()) + (item.conflict() ? "（撞课）" : "")).toList();
        String more = items.size() > lines.size() ? "\n另有 " + (items.size() - lines.size()) + " 门课，请查看课表。" : "";
        return when + " 开课\n" + String.join("\n", lines) + more;
    }

    private record GroupSnapshot(String keys, String fingerprint) { }
}
