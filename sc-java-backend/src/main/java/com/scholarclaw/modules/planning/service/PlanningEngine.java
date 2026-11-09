package com.scholarclaw.modules.planning.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 纯计算的确定性排程。输入时间均为绝对时刻；占用和窗口按半开区间处理。
 * 作息展开、硬约束缓冲、任务资格和快照一致性由调用方负责。
 */
public final class PlanningEngine {
    private static final int MAX_BLOCKS = 2000;
    private PlanningEngine() { }

    public record Interval(Instant start, Instant end) {
        public Interval {
            if (start == null || end == null || !start.isBefore(end)) {
                throw new IllegalArgumentException("时间段起点必须早于终点");
            }
        }
    }
    /** day 是按计划时区计算的日期；同一窗口不得跨越该时区的自然日。 */
    public record Window(LocalDate day, Instant start, Instant end) {
        public Window {
            if (day == null || start == null || end == null || !start.isBefore(end)) {
                throw new IllegalArgumentException("学习时间无效");
            }
        }
    }
    public record Task(String id, String title, Instant deadline, Instant earliest, int remainingMinutes,
                       int priority, boolean splittable, int minBlockMinutes) {
        public Task {
            if (id == null || id.isBlank() || deadline == null || remainingMinutes < 1 || priority < 1
                    || priority > 5 || minBlockMinutes < 1) {
                throw new IllegalArgumentException("任务排程资料无效");
            }
        }
    }
    public record Block(String taskId, String title, LocalDate day, Instant start, Instant end, boolean overdue) { }
    public record Shortage(String taskId, String title, int missingMinutes, String reason, boolean overdue) { }
    public record Result(List<Block> blocks, List<Shortage> shortages) { }

    /**
     * dailyCommittedMinutes 计入已采纳学习块所消耗的每日限额；occupied 包含这些块和其他硬占用。
     * 已逾期任务只生成明确标记的补救建议。所有建议仍需用户确认才能写入。
     */
    public static Result plan(List<Window> windows, List<Interval> occupied, Map<LocalDate, Integer> dailyCommittedMinutes,
                              List<Task> tasks, Instant now, int focusMinutes, int dailyLimitMinutes) {
        if (windows == null || occupied == null || dailyCommittedMinutes == null || tasks == null || now == null
                || focusMinutes < 1 || dailyLimitMinutes < 1) {
            throw new IllegalArgumentException("排程输入无效");
        }
        List<Window> orderedWindows = windows.stream()
                .sorted(Comparator.comparing(Window::start).thenComparing(Window::end).thenComparing(Window::day))
                .toList();
        List<Task> orderedTasks = tasks.stream().sorted(Comparator.comparing(Task::deadline)
                .thenComparingInt(Task::priority).thenComparing(Task::id)).toList();
        List<Interval> busy = new ArrayList<>(occupied);
        Map<LocalDate, Integer> dailyUsed = new HashMap<>(dailyCommittedMinutes);
        List<Block> blocks = new ArrayList<>();
        List<Shortage> shortages = new ArrayList<>();
        Instant firstAllowed = ceilMinute(now);

        for (Task task : orderedTasks) {
            int remaining = task.remainingMinutes();
            boolean overdue = !task.deadline().isAfter(now);
            for (Window window : orderedWindows) {
                if (remaining == 0) break;
                Instant notBefore = task.earliest() == null ? firstAllowed : max(firstAllowed, ceilMinute(task.earliest()));
                Instant limit = overdue ? window.end() : min(window.end(), task.deadline());
                Instant start = max(window.start(), notBefore);
                if (!start.isBefore(limit)) continue;
                for (Interval free : subtract(new Interval(start, limit), busy)) {
                    if (remaining == 0) break;
                    Instant cursor = ceilMinute(free.start());
                    Instant freeEnd = floorMinute(free.end());
                    while (cursor.isBefore(freeEnd) && remaining > 0) {
                        int dailyAvailable = Math.max(0, dailyLimitMinutes - dailyUsed.getOrDefault(window.day(), 0));
                        long intervalMinutes = Duration.between(cursor, freeEnd).toMinutes();
                        int capacity = (int) Math.min(intervalMinutes, dailyAvailable);
                        if (capacity == 0) break;
                        int length;
                        if (!task.splittable()) {
                            if (remaining > capacity) break;
                            length = remaining;
                        } else {
                            // 最小块是硬约束，专注长度是偏好；两者矛盾时优先满足最小块。
                            length = Math.min(remaining, Math.min(capacity, Math.max(focusMinutes, task.minBlockMinutes())));
                            // 最后一段允许小于最小块，避免已完成大部分后无法安排尾数。
                            if (length < task.minBlockMinutes() && length < remaining) break;
                        }
                        Instant end = cursor.plus(length, ChronoUnit.MINUTES);
                        if (blocks.size() >= MAX_BLOCKS) {
                            throw new IllegalArgumentException("建议时间块超过 2000 个，请缩短规划范围或增大专注时间。");
                        }
                        blocks.add(new Block(task.id(), task.title(), window.day(), cursor, end, overdue));
                        busy.add(new Interval(cursor, end));
                        dailyUsed.merge(window.day(), length, Integer::sum);
                        remaining -= length;
                        cursor = end;
                    }
                }
            }
            if (remaining > 0) {
                String reason = task.splittable()
                        ? "可用时间不足，或受每日上限、最小时间块及截止时间限制。"
                        : "缺少足够长的连续可用时间，或受每日上限及截止时间限制。";
                if (overdue) reason = "任务已逾期；补救时间仍不足。" + reason;
                shortages.add(new Shortage(task.id(), task.title(), remaining, reason, overdue));
            }
        }
        blocks.sort(Comparator.comparing(Block::start).thenComparing(Block::taskId));
        return new Result(List.copyOf(blocks), List.copyOf(shortages));
    }

    /** 将硬占用区间从单个窗口扣除；相接不算重叠。 */
    private static List<Interval> subtract(Interval window, List<Interval> busy) {
        List<Interval> relevant = busy.stream().filter(i -> i.start().isBefore(window.end()) && i.end().isAfter(window.start()))
                .sorted(Comparator.comparing(Interval::start).thenComparing(Interval::end)).toList();
        List<Interval> free = new ArrayList<>();
        Instant cursor = window.start();
        for (Interval i : relevant) {
            if (i.start().isAfter(cursor)) free.add(new Interval(cursor, min(i.start(), window.end())));
            cursor = max(cursor, i.end());
            if (!cursor.isBefore(window.end())) break;
        }
        if (cursor.isBefore(window.end())) free.add(new Interval(cursor, window.end()));
        return free;
    }
    private static Instant floorMinute(Instant instant) { return instant.truncatedTo(ChronoUnit.MINUTES); }
    private static Instant ceilMinute(Instant instant) {
        Instant floor = floorMinute(instant);
        return floor.equals(instant) ? floor : floor.plus(1, ChronoUnit.MINUTES);
    }
    private static Instant min(Instant a, Instant b) { return a.isBefore(b) ? a : b; }
    private static Instant max(Instant a, Instant b) { return a.isAfter(b) ? a : b; }
}
