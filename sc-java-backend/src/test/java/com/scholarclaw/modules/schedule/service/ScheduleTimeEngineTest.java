package com.scholarclaw.modules.schedule.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.modules.schedule.service.ScheduleTimeEngine.Change;
import com.scholarclaw.modules.schedule.service.ScheduleTimeEngine.ChangeKind;
import com.scholarclaw.modules.schedule.service.ScheduleTimeEngine.Rule;
import com.scholarclaw.modules.schedule.service.ScheduleTimeEngine.Term;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ScheduleTimeEngineTest {
    private static final Term TERM = new Term(LocalDate.of(2026, 12, 28), 4, ZoneId.of("Asia/Shanghai"));

    @Test
    void expandsSundayAndNonconsecutiveWeeksAcrossYearWithExplicitOffset() {
        Rule sunday = rule(1, 7, "08:00", "09:30", Set.of(1, 3));
        var occurrences = ScheduleTimeEngine.expand(TERM, List.of(sunday), List.of(),
                LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 20));
        assertThat(occurrences).extracting(o -> o.date().toString()).containsExactly("2027-01-03", "2027-01-17");
        assertThat(occurrences.get(0).startAt().toString()).isEqualTo("2027-01-03T08:00+08:00");
        assertThat(occurrences.get(0).originalWeek()).isEqualTo(1);
        assertThat(ScheduleTimeEngine.weekFor(TERM, LocalDate.of(2026, 12, 27))).isNull();
        assertThat(ScheduleTimeEngine.weekFor(TERM, LocalDate.of(2027, 1, 25))).isNull();
    }

    @Test
    void movedEventIsFoundInTargetWeekAndNeverShownTwiceAtOrigin() {
        Rule monday = rule(10, 1, "09:00", "10:00", Set.of(1, 2));
        Change moved = new Change(10, LocalDate.of(2026, 12, 28), ChangeKind.MOVE,
                LocalDate.of(2027, 1, 6), LocalTime.of(14, 0), LocalTime.of(15, 0), "新教室");
        var firstWeek = ScheduleTimeEngine.expand(TERM, List.of(monday), List.of(moved),
                LocalDate.of(2026, 12, 28), LocalDate.of(2027, 1, 3));
        var secondWeek = ScheduleTimeEngine.expand(TERM, List.of(monday), List.of(moved),
                LocalDate.of(2027, 1, 4), LocalDate.of(2027, 1, 10));
        assertThat(firstWeek).isEmpty();
        assertThat(secondWeek).hasSize(2);
        var occurrence = secondWeek.get(1);
        assertThat(occurrence.key()).isEqualTo("10:2026-12-28");
        assertThat(occurrence.originalWeek()).isEqualTo(1);
        assertThat(occurrence.actualWeek()).isEqualTo(2);
        assertThat(occurrence.room()).isEqualTo("新教室");
        assertThat(occurrence.moved()).isTrue();
    }

    @Test
    void cancellationAndHalfOpenConflictsAreHandled() {
        var rules = List.of(rule(1, 1, "08:00", "09:00", Set.of(1)),
                rule(2, 1, "08:30", "09:30", Set.of(1)),
                rule(3, 1, "09:30", "10:00", Set.of(1)),
                rule(4, 1, "08:00", "10:00", Set.of(1)));
        var cancel = new Change(4, TERM.firstMonday(), ChangeKind.CANCEL, null, null, null, null);
        var result = ScheduleTimeEngine.expand(TERM, rules, List.of(cancel), TERM.firstMonday(), TERM.firstMonday());
        assertThat(result).hasSize(3);
        assertThat(result).extracting(ScheduleTimeEngine.Occurrence::conflict).containsExactly(true, true, false);
    }

    @Test
    void daylightSavingGapAndOverlapAreNotSilentlyAdjusted() {
        Term newYork = new Term(LocalDate.of(2026, 3, 2), 2, ZoneId.of("America/New_York"));
        Rule gap = rule(1, 7, "02:30", "03:30", Set.of(1));
        assertThatThrownBy(() -> ScheduleTimeEngine.expand(newYork, List.of(gap), List.of(),
                LocalDate.of(2026, 3, 8), LocalDate.of(2026, 3, 8)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不存在或有歧义");
        Term autumn = new Term(LocalDate.of(2026, 10, 26), 1, ZoneId.of("America/New_York"));
        Rule overlap = rule(2, 7, "01:30", "02:30", Set.of(1));
        assertThatThrownBy(() -> ScheduleTimeEngine.expand(autumn, List.of(overlap), List.of(),
                LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 1)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不存在或有歧义");
    }

    @Test
    void invalidRangeWeeksAndOvernightCoursesAreRejected() {
        assertThatThrownBy(() -> ScheduleTimeEngine.expand(TERM, List.of(), List.of(),
                TERM.firstMonday(), TERM.firstMonday().plusDays(31))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ScheduleTimeEngine.expand(TERM,
                List.of(rule(1, 1, "09:00", "10:00", Set.of(5))), List.of(),
                TERM.firstMonday(), TERM.firstMonday())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ScheduleTimeEngine.expand(TERM,
                List.of(rule(1, 1, "23:00", "01:00", Set.of(1))), List.of(),
                TERM.firstMonday(), TERM.firstMonday())).isInstanceOf(BusinessException.class);
    }

    private static Rule rule(long id, int day, String start, String end, Set<Integer> weeks) {
        return new Rule(id, "课程" + id, "教师", "教室", day, LocalTime.parse(start), LocalTime.parse(end),
                null, null, weeks);
    }
}
