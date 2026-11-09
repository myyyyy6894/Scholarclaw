package com.scholarclaw.modules.planning.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.modules.planning.dto.PlanningInputDtos.EventView;
import com.scholarclaw.modules.planning.dto.PlanningInputDtos.PreferenceView;
import com.scholarclaw.modules.planning.dto.PlanningInputDtos.Window;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlanningTimeTest {
    @Test
    void expandsOnlyExplicitWeekdayInItsOwnTimezone() {
        var preference = new PreferenceView("Asia/Shanghai", List.of(new Window(1, 1080, 1440)), 50, 180, 10, 1);
        var windows = PlanningTime.windows(preference, LocalDate.parse("2026-11-09"), LocalDate.parse("2026-11-15"));
        assertThat(windows).singleElement().satisfies(window -> {
            assertThat(window.day()).isEqualTo(LocalDate.parse("2026-11-09"));
            assertThat(window.start()).isEqualTo(Instant.parse("2026-11-09T10:00:00Z"));
            assertThat(window.end()).isEqualTo(Instant.parse("2026-11-09T16:00:00Z"));
        });
    }

    @Test
    void rejectsMissingAndRepeatedDstWallTimes() {
        ZoneId zone = ZoneId.of("America/New_York");
        assertThatThrownBy(() -> PlanningTime.resolve(LocalDate.parse("2026-03-08"), 150, zone))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不存在或有歧义");
        assertThatThrownBy(() -> PlanningTime.resolve(LocalDate.parse("2026-11-01"), 90, zone))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不存在或有歧义");
        assertThat(PlanningTime.resolve(LocalDate.parse("2026-03-08"), 60, zone))
                .isEqualTo(Instant.parse("2026-03-08T06:00:00Z"));
        assertThat(PlanningTime.resolve(LocalDate.parse("2026-03-08"), 240, zone))
                .isEqualTo(Instant.parse("2026-03-08T08:00:00Z"));
    }

    @Test
    void eventsUseTheirOwnZoneAndOnlyApplicableDates() {
        LocalDate day = LocalDate.parse("2026-11-09");
        var recurring = new EventView("1", "固定会议", "Asia/Shanghai", day, null, 1, 23 * 60, 1440, true, 1);
        var soft = new EventView("2", "可调整日程", "Asia/Shanghai", day, day, null, 12 * 60, 13 * 60, false, 1);
        var outside = new EventView("3", "范围外", "Asia/Shanghai", day, day, null, 60, 120, true, 1);
        var expanded = PlanningTime.events(List.of(recurring, soft, outside), Instant.parse("2026-11-09T00:00:00Z"),
                Instant.parse("2026-11-10T00:00:00Z"));
        assertThat(expanded).hasSize(2);
        assertThat(expanded.get(0).start()).isEqualTo(Instant.parse("2026-11-09T15:00:00Z"));
        assertThat(expanded.get(0).end()).isEqualTo(Instant.parse("2026-11-09T16:00:00Z"));
        assertThat(expanded.get(0).hard()).isTrue();
        assertThat(expanded.get(1).hard()).isFalse();
    }
}
