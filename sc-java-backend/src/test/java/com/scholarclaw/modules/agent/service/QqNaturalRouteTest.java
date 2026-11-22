package com.scholarclaw.modules.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.modules.agent.service.QqNaturalRoute.Action;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class QqNaturalRouteTest {
    @Test
    void localFallbackQueriesAndKeepsLeadTimeSpecific() {
        assertThat(QqNaturalRoute.fallback("我的课前提醒开着吗").action()).isEqualTo(Action.REMINDER_GET);
        assertThat(QqNaturalRoute.fallback("以后上课提前20分钟提醒我").enabled()).isTrue();
        assertThat(QqNaturalRoute.fallback("以后上课提前120分钟提醒我").action()).isEqualTo(Action.UNSUPPORTED);
        assertThat(QqNaturalRoute.fallback("这次上课提前20分钟提醒我").action()).isEqualTo(Action.UNSUPPORTED);
        assertThat(QqNaturalRoute.fallback("别关上课提醒，我只是不想收到作业通知").action()).isEqualTo(Action.IGNORE);
        assertThat(QqNaturalRoute.fallback("明早第一节在哪").action()).isEqualTo(Action.SCHEDULE_QUERY);
    }

    @Test
    void javaRejectsInventedTaskCodeFromModel() throws Exception {
        var forged = new ObjectMapper().readTree("""
                {"action":"DDL_RESCHEDULE","arguments":{"task_hint":"#T123"},"confidence":0.99,"evidence":["数据库作业"]}
                """);
        assertThatThrownBy(() -> QqNaturalRoute.parse(forged, "把数据库作业挪到周五晚上"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void clockUsesExplicitWallTimeAndRequiresDetailsForAnEveningWindow() {
        var monday = LocalDate.of(2026, 11, 23);
        assertThat(QqRelativeTime.date("明早", monday)).isEqualTo(monday.plusDays(1));
        assertThat(QqRelativeTime.date("周五", monday)).isEqualTo(monday.plusDays(4));
        assertThat(QqRelativeTime.clock("晚上", null)).isNull();
        assertThat(QqRelativeTime.clock("八点半", "晚上")).isEqualTo(LocalTime.of(20, 30));
        assertThat(QqRelativeTime.clock("晚上一二点", null)).isNull();
        assertThat(QqRelativeTime.periods("第一、二节")).containsExactly(1, 2);
    }
}
