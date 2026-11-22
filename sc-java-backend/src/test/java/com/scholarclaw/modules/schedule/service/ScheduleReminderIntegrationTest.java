package com.scholarclaw.modules.schedule.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.scholarclaw.modules.channel.service.QqBotSender;
import com.scholarclaw.modules.notification.service.NotificationOutboundScheduler;
import com.scholarclaw.modules.schedule.dto.ScheduleDtos.*;
import com.scholarclaw.modules.schedule.service.ScheduleReminderService.PreferenceWrite;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import com.scholarclaw.support.IsolatedMysqlTestSupport;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@EnabledIfSystemProperty(named = "sc.schedule.integration", matches = "true")
@SpringBootTest(properties = {
        "sc.internal.token=test-internal-token-value",
        "sc.jwt.secret=unit-test-only-jwt-secret-0123456789abcdef",
        "sc.master-key=dW5pdC10ZXN0LW1hc3Rlci1rZXktMzItYnl0ZXMhISE=",
        "sc.mq.ingest.enabled=false", "sc.notification.outbound.enabled=false", "sc.im.push-enabled=true",
        "sc.schedule.reminder.scan-enabled=false", "sc.schedule.import.worker.enabled=false",
        "sc.ddl.reminder.enabled=false", "sc.deletion-gc.enabled=false",
        "sc.dependencies.mysql.pool-probe=false", "sc.dependencies.redis.pool-probe=false"
})
class ScheduleReminderIntegrationTest {
    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 31);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        IsolatedMysqlTestSupport.register(registry);
    }

    @Autowired ScheduleReminderService reminders;
    @Autowired ScheduleService schedules;
    @Autowired UserConfigService users;
    @Autowired NotificationOutboundScheduler outbound;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @MockitoBean QqBotSender sender;

    @Test
    void preplansActualClassesAndOnlyCreatesAtLeadTimeEvenWithConcurrentScans() throws Exception {
        long user = owner();
        var term = term(user, "学期甲");
        rule(user, term, "算法", 8, 0, false);
        rule(user, term, "系统", 8, 0, true);
        rule(user, term, "数学", 8, 10, true);
        clock.at(7, 39);
        assertThat(reminders.preference(user).enabled()).isFalse();
        assertThat(reminders.reconcile(user)).isZero();
        enable(user);
        assertThat(reminders.reconcile(user)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_schedule_reminder WHERE user_id=?", Integer.class, user))
                .isEqualTo(2);
        assertThat(notices(user)).isEmpty();

        clock.at(7, 40);
        var start = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var one = threads.submit(() -> { start.await(); return reminders.reconcile(user); });
            var two = threads.submit(() -> { start.await(); return reminders.reconcile(user); });
            start.countDown();
            assertThat(one.get(10, TimeUnit.SECONDS) + two.get(10, TimeUnit.SECONDS)).isOne();
        }
        assertThat(notices(user)).singleElement().satisfies(notice -> {
            assertThat(notice.content()).contains("算法", "系统", "撞课");
            assertThat(notice.status()).isEqualTo("FALLBACK_WEB_ONLY");
        });
        clock.at(7, 49);
        assertThat(reminders.reconcile(user)).isZero();
        clock.at(7, 50);
        assertThat(reminders.reconcile(user)).isOne();
        assertThat(reminders.reconcile(user)).isZero();
        assertThat(notices(user)).hasSize(2);
        verifyNoInteractions(sender);
    }

    @Test
    void reschedulesPendingGroupsOnPeriodChangeCancelMoveAndTermSwitch() {
        long user = owner();
        var term = term(user, "学期甲");
        long termId = Long.parseLong(term.termId());
        schedules.savePeriods(user, termId, new PeriodsWrite(term.version(),
                List.of(new Period(1, LocalTime.of(8, 0), LocalTime.of(8, 45))), false));
        var periodRule = schedules.createRule(user, new RuleWrite(term.termId(), null, "节次", null, "A101",
                1, null, null, 1, 1, List.of(1), null, false));
        var cancel = rule(user, term, "停课", 8, 5, true);
        var move = rule(user, term, "调课", 8, 10, true);
        enable(user);
        clock.at(7, 20);
        reminders.reconcile(user);
        var latest = schedules.terms(user).getFirst();
        schedules.savePeriods(user, termId, new PeriodsWrite(latest.version(),
                List.of(new Period(1, LocalTime.of(8, 20), LocalTime.of(9, 0))), true));
        schedules.saveException(user, Long.parseLong(cancel.ruleId()), MONDAY,
                new ExceptionWrite("CANCEL", null, null, null, null, null, false));
        schedules.saveException(user, Long.parseLong(move.ruleId()), MONDAY,
                new ExceptionWrite("MOVE", MONDAY, LocalTime.of(8, 30), LocalTime.of(9, 0), "调课教室", null, true));
        clock.at(7, 50);
        assertThat(reminders.reconcile(user)).isZero();
        var other = term(user, "学期乙");
        rule(user, other, "新学期", 8, 25, false);
        clock.at(8, 5);
        assertThat(reminders.reconcile(user)).isOne();
        assertThat(notices(user)).singleElement().satisfies(notice ->
                assertThat(notice.content()).contains("新学期").doesNotContain(periodRule.title(), "停课", "调课"));
    }

    @Test
    void verifiedBindingDndAndLateNoticeRespectOpeningCutoffWithConcurrentOutbound() throws Exception {
        long user = owner();
        bind(user, "00:00:00", "07:45:00");
        var term = term(user, "学期");
        rule(user, term, "线代", 8, 0, false);
        rule(user, term, "物理", 8, 15, true);
        enable(user);
        clock.at(7, 40);
        assertThat(reminders.reconcile(user)).isOne();
        assertThat(notices(user)).singleElement().satisfies(notice -> assertThat(notice.status()).isEqualTo("RATE_LIMITED_DELAYED"));
        assertThat(outbound.dispatchOnce().sent()).isZero();
        clock.at(7, 46);
        when(sender.sendProactive(eq(user), eq(7L), anyString())).thenReturn(true);
        var start = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var one = threads.submit(() -> { start.await(); return outbound.dispatchOnce().sent(); });
            var two = threads.submit(() -> { start.await(); return outbound.dispatchOnce().sent(); });
            start.countDown();
            assertThat(one.get(10, TimeUnit.SECONDS) + two.get(10, TimeUnit.SECONDS)).isOne();
        }
        verify(sender, times(1)).sendProactive(eq(user), eq(7L), contains("14 分钟"));
        clock.at(7, 55);
        assertThat(reminders.reconcile(user)).isOne();
        clock.at(8, 15);
        assertThat(outbound.dispatchOnce().sent()).isZero();
        assertThat(notices(user)).extracting(Notice::status).containsExactly("SENT_IM", "FALLBACK_WEB_ONLY");
    }

    @Test
    void unsentMessageIsRecheckedAfterCancellationAndUnknownDeliveryDoesNotRetry() {
        long user = owner();
        bind(user, "00:00:00", "00:00:00");
        var term = term(user, "学期");
        var removed = rule(user, term, "停课", 8, 0, false);
        rule(user, term, "第二节", 8, 40, true);
        rule(user, term, "重启", 9, 15, true);
        enable(user);
        clock.at(7, 40);
        reminders.reconcile(user);
        schedules.saveException(user, Long.parseLong(removed.ruleId()), MONDAY,
                new ExceptionWrite("CANCEL", null, null, null, null, null, false));
        assertThat(outbound.dispatchOnce().sent()).isZero();
        verifyNoInteractions(sender);

        clock.at(8, 20);
        reminders.reconcile(user);
        when(sender.sendProactive(eq(user), eq(7L), anyString())).thenThrow(new IllegalStateException("fake timeout"));
        assertThat(outbound.dispatchOnce().fallback()).isOne();
        clock.at(8, 22);
        assertThat(outbound.dispatchOnce().sent()).isZero();
        verify(sender, times(1)).sendProactive(eq(user), eq(7L), contains("第二节"));
        assertThat(notices(user)).hasSize(2).allSatisfy(notice -> assertThat(notice.status()).isEqualTo("FALLBACK_WEB_ONLY"));
        assertThat(notices(user).getLast().error()).contains("投递结果未知");

        clock.at(8, 55);
        reminders.reconcile(user);
        // Simulate a process that recorded the transport start, then died with its lease persisted.
        jdbc.update("""
                UPDATE t_notification_log SET schedule_send_started=1,scheduled_push_at='2026-08-31 08:56:00'
                WHERE user_id=? AND content LIKE '%重启%'
                """, user);
        clock.at(8, 57);
        assertThat(outbound.dispatchOnce().fallback()).isOne();
        verifyNoMoreInteractions(sender);
        assertThat(notices(user).getLast().error()).contains("投递结果未知");
    }

    @Test
    void quietWindowPastOpeningAndDisabledPreferenceKeepWebOnly() {
        long user = owner();
        bind(user, "00:00:00", "08:01:00");
        var term = term(user, "学期");
        rule(user, term, "线代", 8, 0, false);
        rule(user, term, "第二节", 8, 30, true);
        enable(user);
        clock.at(7, 40);
        reminders.reconcile(user);
        assertThat(notices(user).getFirst().error()).contains("免打扰");
        clock.at(8, 10);
        reminders.reconcile(user);
        var prior = reminders.preference(user);
        reminders.setPreference(user, new PreferenceWrite(false, prior.version()));
        outbound.dispatchOnce();
        assertThat(notices(user)).hasSize(2).allSatisfy(notice -> assertThat(notice.status()).isEqualTo("FALLBACK_WEB_ONLY"));
        verifyNoInteractions(sender);
    }

    private void enable(long user) { reminders.setPreference(user, new PreferenceWrite(true, 0L)); }
    private long owner() { return users.createUserAccount("reminder_" + System.nanoTime(), "hash", "Asia/Shanghai").getUserId(); }
    private TermView term(long user, String name) {
        return schedules.createTerm(user, new TermWrite(name, MONDAY, 16, "Asia/Shanghai", true, null, false));
    }
    private RuleView rule(long user, TermView term, String name, int hour, int minute, boolean overlap) {
        var start = LocalTime.of(hour, minute);
        return schedules.createRule(user, new RuleWrite(term.termId(), null, name, null, "A101", 1,
                start, start.plusMinutes(45), null, null, List.of(1), null, overlap));
    }
    private void bind(long user, String from, String to) {
        jdbc.update("""
                UPDATE t_user_config SET qqbot_app_id=?,qqbot_secret_cipher='test-fixture',qqbot_openid='fixture',
                  qqbot_binding_verified=1,qqbot_binding_version=7,dnd_start_time=?,dnd_end_time=? WHERE user_id=?
                """, Long.toString(9_000_000 + user), from, to, user);
    }
    private List<Notice> notices(long user) {
        return jdbc.query("""
                SELECT content,outbound_status,im_error_msg FROM t_notification_log
                WHERE user_id=? AND biz_type='SCHEDULE_T20M' ORDER BY notification_id
                """, (rs, row) -> new Notice(rs.getString(1), rs.getString(2), rs.getString(3)), user);
    }
    private record Notice(String content, String status, String error) { }

    @TestConfiguration
    static class TestClockConfig {
        @Bean @Primary
        MutableClock scheduleTestClock() { return new MutableClock(); }
    }

    static class MutableClock extends Clock {
        private volatile Instant now = MONDAY.atStartOfDay(ZONE).toInstant();
        void at(int hour, int minute) { now = MONDAY.atTime(hour, minute).atZone(ZONE).toInstant(); }
        @Override public ZoneId getZone() { return ZONE; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }
}
