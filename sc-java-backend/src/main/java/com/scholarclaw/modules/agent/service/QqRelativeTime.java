package com.scholarclaw.modules.agent.service;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.regex.Pattern;

/** The user's local date and clock are resolved here from literal evidence, never from model UTC output. */
final class QqRelativeTime {
    private static final String NUMBER = "(?:[0-9]{1,2}|[零一二两三四五六七八九十]{1,3})";
    private static final String WINDOW = "凌晨|早上|早晨|上午|中午|下午|傍晚|晚上";
    private static final Pattern DATE = Pattern.compile(
            "[0-9]{4}-[0-9]{2}-[0-9]{2}|大后天|后天|明天|明早|明晚|今天|今早|今晚|"
                    + "(?:下|本|这)?(?:星期|周)[一二三四五六日天]|下周|本周|这周");
    private static final Pattern CLOCK = Pattern.compile(
            "(?:(?<window>" + WINDOW + ")\\s*)?(?<hour>" + NUMBER + ")(?:[:：](?<minute>[0-9]{2})"
                    + "|[点时](?:(?<half>半)|(?<spoken>" + NUMBER + ")分?)?)");
    private static final Pattern CLOCK_FRAGMENT = Pattern.compile(CLOCK.pattern() + "|" + WINDOW);
    private static final Pattern PERIOD = Pattern.compile("第?(" + NUMBER + ")(?:[至到~～—\\-,、]+(?:第)?(" + NUMBER + "))?节");
    private static final Pattern DAY = Pattern.compile("([下本这])?(?:周|星期)([一二三四五六日天])");

    private QqRelativeTime() { }

    static String dateFragment(String text) { return first(DATE, text); }
    static String clockFragment(String text) { return first(CLOCK_FRAGMENT, text); }
    static String periodFragment(String text) { return first(PERIOD, text); }
    private static String first(Pattern pattern, String text) {
        var match = pattern.matcher(text);
        return match.find() ? match.group() : null;
    }

    static LocalDate date(String text, LocalDate reference) {
        if (text == null) return null;
        String value = dateFragment(text);
        if (value == null) return null;
        return switch (value) {
            case "今天", "今早", "今晚" -> reference;
            case "明天", "明早", "明晚" -> reference.plusDays(1);
            case "后天" -> reference.plusDays(2);
            case "大后天" -> reference.plusDays(3);
            case "本周", "这周" -> reference.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case "下周" -> reference.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
            default -> dateOrWeekday(value, reference);
        };
    }

    private static LocalDate dateOrWeekday(String value, LocalDate reference) {
        var day = DAY.matcher(value);
        if (day.matches()) {
            var weekday = DayOfWeek.of("一二三四五六日".indexOf(day.group(2).replace("天", "日")) + 1);
            String modifier = day.group(1);
            if (modifier == null) return reference.with(TemporalAdjusters.nextOrSame(weekday));
            LocalDate monday = reference.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            return monday.plusDays(weekday.getValue() - 1L).plusWeeks("下".equals(modifier) ? 1 : 0);
        }
        try { return LocalDate.parse(value); }
        catch (DateTimeException e) { return null; }
    }

    static LocalTime clock(String value, String inheritedWindow) {
        if (value == null) return null;
        var match = CLOCK.matcher(value.strip());
        if (!match.matches()) return null;
        int hour = number(match.group("hour"));
        if (hour < 0) return null;
        String window = match.group("window") == null ? inheritedWindow : match.group("window");
        if (window != null && window.matches("下午|傍晚|晚上") && hour < 12) hour += 12;
        if ("凌晨".equals(window) && hour == 12) hour = 0;
        if ("中午".equals(window) && hour > 0 && hour < 6) hour += 12;
        int minute = match.group("half") != null ? 30 : match.group("minute") != null ? number(match.group("minute"))
                : match.group("spoken") != null ? number(match.group("spoken")) : 0;
        try { return LocalTime.of(hour, minute); }
        catch (DateTimeException e) { return null; }
    }

    static String window(String text) {
        if (text == null) return null;
        if (text.contains("明早") || text.contains("今早")) return "早上";
        if (text.contains("明晚") || text.contains("今晚")) return "晚上";
        return first(Pattern.compile(WINDOW), text);
    }

    static int[] periods(String expression) {
        if (expression == null) return null;
        var match = PERIOD.matcher(expression);
        if (!match.find()) return null;
        int start = number(match.group(1));
        int end = match.group(2) == null ? start : number(match.group(2));
        return 1 <= start && start <= end && end <= 30 ? new int[]{start, end} : null;
    }

    static int number(String value) {
        if (value.matches("[0-9]{1,2}")) return Integer.parseInt(value);
        String digits = "零一二三四五六七八九";
        String normalized = value.replace('两', '二');
        int ten = normalized.indexOf('十');
        if (ten == -1) return normalized.length() == 1 ? digits.indexOf(normalized.charAt(0)) : -1;
        int tens = ten == 0 ? 1 : digits.indexOf(normalized.charAt(0));
        int units = ten + 1 >= normalized.length() ? 0 : digits.indexOf(normalized.charAt(ten + 1));
        return tens < 0 || units < 0 || ten > 1 || normalized.length() > ten + 2 ? -1 : tens * 10 + units;
    }
}
