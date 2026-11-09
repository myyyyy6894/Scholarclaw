package com.scholarclaw.modules.planning.repository;

import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class PlanningInputRepository {
    public record Preference(long userId, String timezone, String windowsJson, int focusMinutes,
                             int dailyLimitMinutes, int bufferMinutes, long version) { }
    public record Event(long id, long userId, String title, String timezone, LocalDate startDate, LocalDate endDate,
                        Integer weekday, int startMinute, int endMinute, boolean hard, long version) { }
    public record Profile(long userId, long taskId, int estimatedMinutes, int remainingMinutes, int priority,
                          LocalDateTime earliestStartAt, boolean splittable, int minBlockMinutes, long version) { }
    private static final RowMapper<Preference> PREFERENCE = (rs, n) -> new Preference(rs.getLong("user_id"),
            rs.getString("timezone"), rs.getString("windows_json"), rs.getInt("focus_minutes"),
            rs.getInt("daily_limit_minutes"), rs.getInt("buffer_minutes"), rs.getLong("version"));
    private static final RowMapper<Event> EVENT = (rs, n) -> new Event(rs.getLong("event_id"), rs.getLong("user_id"),
            rs.getString("title"), rs.getString("timezone"), rs.getObject("start_date", LocalDate.class),
            rs.getObject("end_date", LocalDate.class), rs.getObject("weekday", Integer.class),
            rs.getInt("start_minute"), rs.getInt("end_minute"), rs.getBoolean("hard"), rs.getLong("version"));
    private static final RowMapper<Profile> PROFILE = (rs, n) -> new Profile(rs.getLong("user_id"), rs.getLong("task_id"),
            rs.getInt("estimated_minutes"), rs.getInt("remaining_minutes"), rs.getInt("priority"),
            rs.getObject("earliest_start_utc", LocalDateTime.class), rs.getBoolean("splittable"),
            rs.getInt("min_block_minutes"), rs.getLong("version"));
    private final JdbcTemplate jdbc;
    public PlanningInputRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<Preference> preference(long userId) {
        return jdbc.query("SELECT * FROM t_planning_preference WHERE user_id=?", PREFERENCE, userId).stream().findFirst();
    }
    public void insertPreference(Preference p, LocalDateTime now) {
        jdbc.update("INSERT INTO t_planning_preference (user_id,timezone,windows_json,focus_minutes,daily_limit_minutes,buffer_minutes,updated_at) VALUES (?,?,?,?,?,?,?)",
                p.userId(), p.timezone(), p.windowsJson(), p.focusMinutes(), p.dailyLimitMinutes(), p.bufferMinutes(), now);
    }
    public int updatePreference(Preference p, long version, LocalDateTime now) {
        return jdbc.update("UPDATE t_planning_preference SET timezone=?,windows_json=?,focus_minutes=?,daily_limit_minutes=?,buffer_minutes=?,version=version+1,updated_at=? WHERE user_id=? AND version=?",
                p.timezone(), p.windowsJson(), p.focusMinutes(), p.dailyLimitMinutes(), p.bufferMinutes(), now, p.userId(), version);
    }
    public List<Event> events(long userId) {
        return jdbc.query("SELECT * FROM t_personal_event WHERE user_id=? ORDER BY start_date,event_id", EVENT, userId);
    }
    public Optional<Event> event(long userId, long id) {
        return jdbc.query("SELECT * FROM t_personal_event WHERE user_id=? AND event_id=?", EVENT, userId, id).stream().findFirst();
    }
    public long insertEvent(Event e, LocalDateTime now) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("INSERT INTO t_personal_event (user_id,title,timezone,start_date,end_date,weekday,start_minute,end_minute,hard,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, e.userId()); ps.setString(2, e.title()); ps.setString(3, e.timezone());
            ps.setObject(4, e.startDate()); ps.setObject(5, e.endDate()); ps.setObject(6, e.weekday());
            ps.setInt(7, e.startMinute()); ps.setInt(8, e.endMinute()); ps.setBoolean(9, e.hard());
            ps.setObject(10, now); ps.setObject(11, now); return ps;
        }, keys);
        Number id = keys.getKey();
        if (id == null) throw new IllegalStateException("日程未返回 ID");
        return id.longValue();
    }
    public int updateEvent(Event e, long version, LocalDateTime now) {
        return jdbc.update("UPDATE t_personal_event SET title=?,timezone=?,start_date=?,end_date=?,weekday=?,start_minute=?,end_minute=?,hard=?,version=version+1,updated_at=? WHERE user_id=? AND event_id=? AND version=?",
                e.title(), e.timezone(), e.startDate(), e.endDate(), e.weekday(), e.startMinute(), e.endMinute(),
                e.hard(), now, e.userId(), e.id(), version);
    }
    public int deleteEvent(long userId, long id, long version) {
        return jdbc.update("DELETE FROM t_personal_event WHERE user_id=? AND event_id=? AND version=?", userId, id, version);
    }
    public List<Profile> profiles(long userId) {
        return jdbc.query("SELECT * FROM t_task_planning_profile WHERE user_id=? ORDER BY task_id", PROFILE, userId);
    }
    public Optional<Profile> profile(long userId, long taskId) {
        return jdbc.query("SELECT * FROM t_task_planning_profile WHERE user_id=? AND task_id=?", PROFILE, userId, taskId)
                .stream().findFirst();
    }
    public void insertProfile(Profile p, LocalDateTime now) {
        jdbc.update("INSERT INTO t_task_planning_profile (user_id,task_id,estimated_minutes,remaining_minutes,priority,earliest_start_utc,splittable,min_block_minutes,updated_at) VALUES (?,?,?,?,?,?,?,?,?)",
                p.userId(), p.taskId(), p.estimatedMinutes(), p.remainingMinutes(), p.priority(), p.earliestStartAt(),
                p.splittable(), p.minBlockMinutes(), now);
    }
    public int updateProfile(Profile p, long version, LocalDateTime now) {
        return jdbc.update("UPDATE t_task_planning_profile SET estimated_minutes=?,remaining_minutes=?,priority=?,earliest_start_utc=?,splittable=?,min_block_minutes=?,version=version+1,updated_at=? WHERE user_id=? AND task_id=? AND version=?",
                p.estimatedMinutes(), p.remainingMinutes(), p.priority(), p.earliestStartAt(), p.splittable(),
                p.minBlockMinutes(), now, p.userId(), p.taskId(), version);
    }
    public int deleteProfile(long userId, long taskId, long version) {
        return jdbc.update("DELETE FROM t_task_planning_profile WHERE user_id=? AND task_id=? AND version=?", userId, taskId, version);
    }
    public void deleteUserData(long userId) {
        jdbc.update("DELETE FROM t_task_planning_profile WHERE user_id=?", userId);
        jdbc.update("DELETE FROM t_personal_event WHERE user_id=?", userId);
        jdbc.update("DELETE FROM t_planning_preference WHERE user_id=?", userId);
    }
}
