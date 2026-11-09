package com.scholarclaw.modules.schedule.repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** 课表域自己的参数化 SQL；所有按资源读取和写入均限定用户。 */
@Repository
public class ScheduleRepository {
    public record Term(long id, long userId, String name, LocalDate firstMonday, int weekCount,
                       String timezone, boolean active, long version, long scheduleVersion) { }
    public record Period(int number, LocalTime start, LocalTime end) { }
    public record Rule(long id, long userId, long termId, Long courseId, String title, String teacher,
                       String room, int weekday, LocalTime start, LocalTime end, Integer startPeriod,
                       Integer endPeriod, String weeksJson, long version) { }
    public record ExceptionRow(long id, long userId, long termId, long ruleId, LocalDate originalDate,
                               String kind, LocalDate targetDate, LocalTime start, LocalTime end,
                               String room, long version) { }

    private final JdbcTemplate jdbc;

    public ScheduleRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final org.springframework.jdbc.core.RowMapper<Term> TERM = (rs, row) -> new Term(
            rs.getLong("term_id"), rs.getLong("user_id"), rs.getString("name"),
            rs.getObject("first_monday", LocalDate.class), rs.getInt("week_count"), rs.getString("timezone"),
            rs.getBoolean("is_active"), rs.getLong("version"), rs.getLong("schedule_version"));
    private static final org.springframework.jdbc.core.RowMapper<Period> PERIOD = (rs, row) -> new Period(
            rs.getInt("period_no"), rs.getObject("start_time", LocalTime.class), rs.getObject("end_time", LocalTime.class));
    private static final org.springframework.jdbc.core.RowMapper<Rule> RULE = (rs, row) -> new Rule(
            rs.getLong("rule_id"), rs.getLong("user_id"), rs.getLong("term_id"), rs.getObject("course_id", Long.class),
            rs.getString("title"), rs.getString("teacher"), rs.getString("room"), rs.getInt("weekday"),
            rs.getObject("start_time", LocalTime.class), rs.getObject("end_time", LocalTime.class),
            rs.getObject("start_period", Integer.class), rs.getObject("end_period", Integer.class),
            rs.getString("weeks_json"), rs.getLong("version"));
    private static final org.springframework.jdbc.core.RowMapper<ExceptionRow> EXCEPTION = (rs, row) -> new ExceptionRow(
            rs.getLong("exception_id"), rs.getLong("user_id"), rs.getLong("term_id"), rs.getLong("rule_id"),
            rs.getObject("original_date", LocalDate.class), rs.getString("kind"),
            rs.getObject("target_date", LocalDate.class), rs.getObject("target_start_time", LocalTime.class),
            rs.getObject("target_end_time", LocalTime.class), rs.getString("target_room"), rs.getLong("version"));

    public List<Term> terms(long userId) {
        return jdbc.query("SELECT * FROM t_schedule_term WHERE user_id=? ORDER BY is_active DESC, first_monday DESC, term_id DESC", TERM, userId);
    }
    public Optional<Term> term(long userId, long termId) {
        return jdbc.query("SELECT * FROM t_schedule_term WHERE user_id=? AND term_id=?", TERM, userId, termId).stream().findFirst();
    }
    public Optional<Term> termForUpdate(long userId, long termId) {
        return jdbc.query("SELECT * FROM t_schedule_term WHERE user_id=? AND term_id=? FOR UPDATE", TERM, userId, termId)
                .stream().findFirst();
    }
    public void deactivateTerms(long userId, Long except, LocalDateTime now) {
        if (except == null) {
            jdbc.update("UPDATE t_schedule_term SET is_active=0,version=version+1,updated_at=? WHERE user_id=? AND is_active=1", now, userId);
        } else {
            jdbc.update("UPDATE t_schedule_term SET is_active=0,version=version+1,updated_at=? WHERE user_id=? AND is_active=1 AND term_id<>?", now, userId, except);
        }
    }
    public long insertTerm(long userId, String name, LocalDate firstMonday, int weeks, String zone,
                           boolean active, LocalDateTime now) {
        return insert("INSERT INTO t_schedule_term (user_id,name,first_monday,week_count,timezone,is_active,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?)",
                userId, name, firstMonday, weeks, zone, active, now, now);
    }
    public int updateTerm(long userId, long termId, long version, String name, LocalDate firstMonday,
                          int weeks, String zone, boolean active, LocalDateTime now) {
        return jdbc.update("UPDATE t_schedule_term SET name=?,first_monday=?,week_count=?,timezone=?,is_active=?,version=version+1,schedule_version=schedule_version+1,updated_at=? WHERE user_id=? AND term_id=? AND version=?",
                name, firstMonday, weeks, zone, active, now, userId, termId, version);
    }
    public void touchTerm(long userId, long termId, LocalDateTime now) {
        jdbc.update("UPDATE t_schedule_term SET version=version+1,schedule_version=schedule_version+1,updated_at=? WHERE user_id=? AND term_id=?", now, userId, termId);
    }
    public List<Period> periods(long userId, long termId) {
        return jdbc.query("SELECT * FROM t_schedule_period WHERE user_id=? AND term_id=? ORDER BY period_no", PERIOD, userId, termId);
    }
    public void replacePeriods(long userId, long termId, List<Period> periods) {
        jdbc.update("DELETE FROM t_schedule_period WHERE user_id=? AND term_id=?", userId, termId);
        for (Period period : periods) {
            jdbc.update("INSERT INTO t_schedule_period (user_id,term_id,period_no,start_time,end_time) VALUES (?,?,?,?,?)",
                    userId, termId, period.number(), period.start(), period.end());
        }
    }
    public List<Rule> rules(long userId, long termId) {
        return jdbc.query("SELECT * FROM t_schedule_rule WHERE user_id=? AND term_id=? ORDER BY weekday,start_time,rule_id", RULE, userId, termId);
    }
    public Optional<Rule> rule(long userId, long ruleId) {
        return jdbc.query("SELECT * FROM t_schedule_rule WHERE user_id=? AND rule_id=?", RULE, userId, ruleId).stream().findFirst();
    }
    public long insertRule(Rule value, LocalDateTime now) {
        return insert("INSERT INTO t_schedule_rule (user_id,term_id,course_id,title,teacher,room,weekday,start_time,end_time,start_period,end_period,weeks_json,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                value.userId(), value.termId(), value.courseId(), value.title(), value.teacher(), value.room(), value.weekday(),
                value.start(), value.end(), value.startPeriod(), value.endPeriod(), value.weeksJson(), now, now);
    }
    public int updateRule(Rule value, long version, LocalDateTime now) {
        return jdbc.update("UPDATE t_schedule_rule SET course_id=?,title=?,teacher=?,room=?,weekday=?,start_time=?,end_time=?,start_period=?,end_period=?,weeks_json=?,version=version+1,updated_at=? WHERE user_id=? AND rule_id=? AND version=?",
                value.courseId(), value.title(), value.teacher(), value.room(), value.weekday(), value.start(), value.end(),
                value.startPeriod(), value.endPeriod(), value.weeksJson(), now, value.userId(), value.id(), version);
    }
    public int updateRuleTimes(long userId, long ruleId, LocalTime start, LocalTime end, LocalDateTime now) {
        return jdbc.update("UPDATE t_schedule_rule SET start_time=?,end_time=?,version=version+1,updated_at=? WHERE user_id=? AND rule_id=?",
                start, end, now, userId, ruleId);
    }
    public void deleteRule(long userId, long ruleId) {
        jdbc.update("DELETE FROM t_schedule_exception WHERE user_id=? AND rule_id=?", userId, ruleId);
        jdbc.update("DELETE FROM t_schedule_rule WHERE user_id=? AND rule_id=?", userId, ruleId);
    }
    public List<ExceptionRow> exceptions(long userId, long termId) {
        return jdbc.query("SELECT * FROM t_schedule_exception WHERE user_id=? AND term_id=? ORDER BY original_date,exception_id", EXCEPTION, userId, termId);
    }
    public Optional<ExceptionRow> exception(long userId, long ruleId, LocalDate date) {
        return jdbc.query("SELECT * FROM t_schedule_exception WHERE user_id=? AND rule_id=? AND original_date=?", EXCEPTION, userId, ruleId, date).stream().findFirst();
    }
    public long insertException(ExceptionRow value, LocalDateTime now) {
        return insert("INSERT INTO t_schedule_exception (user_id,term_id,rule_id,original_date,kind,target_date,target_start_time,target_end_time,target_room,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                value.userId(), value.termId(), value.ruleId(), value.originalDate(), value.kind(), value.targetDate(),
                value.start(), value.end(), value.room(), now, now);
    }
    public int updateException(ExceptionRow value, long version, LocalDateTime now) {
        return jdbc.update("UPDATE t_schedule_exception SET kind=?,target_date=?,target_start_time=?,target_end_time=?,target_room=?,version=version+1,updated_at=? WHERE user_id=? AND exception_id=? AND version=?",
                value.kind(), value.targetDate(), value.start(), value.end(), value.room(), now,
                value.userId(), value.id(), version);
    }
    public void deleteException(long userId, long exceptionId) {
        jdbc.update("DELETE FROM t_schedule_exception WHERE user_id=? AND exception_id=?", userId, exceptionId);
    }
    public void unbindCourse(long userId, long courseId, LocalDateTime now) {
        jdbc.update("UPDATE t_schedule_term SET version=version+1,schedule_version=schedule_version+1,updated_at=? WHERE user_id=? AND term_id IN (SELECT term_id FROM t_schedule_rule WHERE user_id=? AND course_id=?)", now, userId, userId, courseId);
        jdbc.update("UPDATE t_schedule_rule SET course_id=NULL,version=version+1,updated_at=? WHERE user_id=? AND course_id=?", now, userId, courseId);
    }
    public void deleteUserData(long userId) {
        for (String table : List.of("t_schedule_exception", "t_schedule_rule", "t_schedule_period", "t_schedule_term")) {
            jdbc.update("DELETE FROM " + table + " WHERE user_id=?", userId);
        }
    }
    private long insert(String sql, Object... values) {
        var holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            return statement;
        }, holder);
        Number key = holder.getKey();
        if (key == null) throw new IllegalStateException("课表记录未返回ID");
        return key.longValue();
    }
}
