package com.scholarclaw.modules.planning.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.scholarclaw.modules.planning.service.PlanningEngine.Block;
import com.scholarclaw.modules.planning.service.PlanningEngine.Interval;
import com.scholarclaw.modules.planning.service.PlanningEngine.Task;
import com.scholarclaw.modules.planning.service.PlanningEngine.Window;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlanningEngineTest {
    private static final LocalDate MONDAY = LocalDate.parse("2026-11-09");
    private static final Instant NOW = at("08:00:00");

    @Test
    void schedulesAroundHardOccupancyAndRespectsDeadlineRatherThanTreatingItAsAnEvent() {
        var result = PlanningEngine.plan(List.of(window("09:00:00", "13:00:00")),
                List.of(interval("09:30:00", "10:30:00")), Map.of(),
                List.of(task("1", "12:00:00", 120, true, 30)), NOW, 60, 180);
        assertThat(result.shortages()).isEmpty();
        assertThat(result.blocks()).extracting(Block::start, Block::end).containsExactly(
                org.assertj.core.groups.Tuple.tuple(at("09:00:00"), at("09:30:00")),
                org.assertj.core.groups.Tuple.tuple(at("10:30:00"), at("11:30:00")),
                org.assertj.core.groups.Tuple.tuple(at("11:30:00"), at("12:00:00")));
    }

    @Test
    void respectsExistingDailyCapacityAndExplainsShortageWithoutOverlapping() {
        var result = PlanningEngine.plan(List.of(window("09:00:00", "12:00:00")),
                List.of(interval("09:00:00", "09:30:00")), Map.of(MONDAY, 30),
                List.of(task("1", "12:00:00", 120, true, 30)), NOW, 60, 90);
        assertThat(result.blocks()).hasSize(1);
        assertThat(result.blocks().getFirst().start()).isEqualTo(at("09:30:00"));
        assertThat(result.blocks().getFirst().end()).isEqualTo(at("10:30:00"));
        assertThat(result.shortages()).singleElement().satisfies(shortage -> {
            assertThat(shortage.missingMinutes()).isEqualTo(60);
            assertThat(shortage.reason()).contains("每日上限");
        });
    }

    @Test
    void continuousTaskRequiresOneSufficientUninterruptedWindow() {
        var result = PlanningEngine.plan(List.of(window("09:00:00", "11:00:00")),
                List.of(interval("09:45:00", "10:00:00")), Map.of(),
                List.of(task("1", "11:00:00", 75, false, 30)), NOW, 30, 180);
        assertThat(result.blocks()).isEmpty();
        assertThat(result.shortages()).singleElement().satisfies(shortage -> {
            assertThat(shortage.missingMinutes()).isEqualTo(75);
            assertThat(shortage.reason()).contains("连续");
        });
    }

    @Test
    void minBlockOverridesSoftFocusPreferenceAndAllowsShortFinalRemainder() {
        var result = PlanningEngine.plan(List.of(window("09:00:00", "11:00:00")), List.of(), Map.of(),
                List.of(task("1", "11:00:00", 75, true, 30)), NOW, 20, 180);
        assertThat(result.shortages()).isEmpty();
        assertThat(result.blocks()).extracting(b -> java.time.Duration.between(b.start(), b.end()).toMinutes())
                .containsExactly(30L, 30L, 15L);
    }

    @Test
    void overdueWorkIsExplicitlyMarkedAsRecoveryInsteadOfPretendingToMeetDeadline() {
        var result = PlanningEngine.plan(List.of(window("09:00:00", "09:45:00")), List.of(), Map.of(),
                List.of(task("1", "07:00:00", 60, true, 30)), NOW, 60, 180);
        assertThat(result.blocks()).singleElement().satisfies(block -> assertThat(block.overdue()).isTrue());
        assertThat(result.shortages()).singleElement().satisfies(shortage -> {
            assertThat(shortage.overdue()).isTrue();
            assertThat(shortage.missingMinutes()).isEqualTo(15);
            assertThat(shortage.reason()).contains("已逾期");
        });
    }

    @Test
    void sortsByDeadlineThenPriorityAndNeverSchedulesBeforeEarliestOrInThePast() {
        Task first = new Task("2", "优先", at("12:00:00"), at("10:00:35"), 30, 1, true, 30);
        Task second = new Task("1", "普通", at("12:00:00"), null, 30, 3, true, 30);
        var result = PlanningEngine.plan(List.of(window("08:00:00", "12:00:00")), List.of(), Map.of(),
                List.of(second, first), at("09:00:01"), 60, 180);
        assertThat(result.blocks()).extracting(Block::taskId, Block::start, Block::end).containsExactly(
                org.assertj.core.groups.Tuple.tuple("1", at("09:01:00"), at("09:31:00")),
                org.assertj.core.groups.Tuple.tuple("2", at("10:01:00"), at("10:31:00")));
    }

    @Test
    void mergesOverlappingBusyIntervalsAndHandlesAdjacentBoundaries() {
        var result = PlanningEngine.plan(List.of(window("09:00:00", "12:00:00")),
                List.of(interval("09:30:00", "10:30:00"), interval("10:00:00", "11:00:00")), Map.of(),
                List.of(task("1", "12:00:00", 90, true, 30)), NOW, 60, 180);
        assertThat(result.shortages()).isEmpty();
        assertThat(result.blocks()).extracting(Block::start, Block::end).containsExactly(
                org.assertj.core.groups.Tuple.tuple(at("09:00:00"), at("09:30:00")),
                org.assertj.core.groups.Tuple.tuple(at("11:00:00"), at("12:00:00")));
    }

    private static Instant at(String time) { return Instant.parse("2026-11-09T" + time + "Z"); }
    private static Window window(String start, String end) { return new Window(MONDAY, at(start), at(end)); }
    private static Interval interval(String start, String end) { return new Interval(at(start), at(end)); }
    private static Task task(String id, String deadline, int remaining, boolean split, int minimum) {
        return new Task(id, "任务" + id, at(deadline), null, remaining, 3, split, minimum);
    }
}
