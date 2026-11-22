package com.scholarclaw.modules.schedule.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Schedule is the single writer for reminder preferences and occurrence groups. */
@Repository
public class ScheduleReminderRepository {
    public record Preference(boolean enabled, long version) { }
    public record Reminder(long id, long userId, long termId, long startEpoch, long scheduleVersion,
                           String fingerprint, boolean active, Long notificationId) { }
    private static final RowMapper<Reminder> REMINDER = (rs, number) -> new Reminder(
            rs.getLong("reminder_id"), rs.getLong("user_id"), rs.getLong("term_id"), rs.getLong("start_epoch"),
            rs.getLong("schedule_version"), rs.getString("group_fingerprint"), rs.getBoolean("active"),
            rs.getObject("notification_id", Long.class));
    private final JdbcTemplate jdbc;

    public ScheduleReminderRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Preference preference(long userId) {
        return jdbc.query("SELECT enabled,version FROM t_schedule_reminder_pref WHERE user_id=?",
                (rs, row) -> new Preference(rs.getBoolean("enabled"), rs.getLong("version")), userId)
                .stream().findFirst().orElse(new Preference(false, 0));
    }

    public Preference setPreference(long userId, boolean enabled, long priorVersion) {
        long version = priorVersion + 1;
        if (priorVersion == 0) {
            jdbc.update("INSERT INTO t_schedule_reminder_pref(user_id,enabled,version) VALUES (?,?,?)", userId, enabled, version);
        } else {
            jdbc.update("UPDATE t_schedule_reminder_pref SET enabled=?,version=? WHERE user_id=?", enabled, version, userId);
        }
        return new Preference(enabled, version);
    }

    public List<Long> enabledOwners(long after, int limit) {
        return jdbc.queryForList("""
                SELECT user_id FROM t_schedule_reminder_pref WHERE enabled=1 AND user_id>?
                ORDER BY user_id LIMIT ?
                """, Long.class, after, limit);
    }

    public List<Reminder> future(long userId, long now) {
        return jdbc.query("SELECT * FROM t_schedule_reminder WHERE user_id=? AND start_epoch>?", REMINDER, userId, now);
    }

    public Optional<Reminder> get(long userId, long id) {
        return jdbc.query("SELECT * FROM t_schedule_reminder WHERE user_id=? AND reminder_id=?", REMINDER, userId, id)
                .stream().findFirst();
    }

    public Reminder insert(long userId, long termId, long epoch, long version, String fingerprint, String keys) {
        jdbc.update("""
                INSERT INTO t_schedule_reminder(user_id,term_id,start_epoch,schedule_version,group_fingerprint,member_keys)
                VALUES (?,?,?,?,?,?)
                """, userId, termId, epoch, version, fingerprint, keys);
        return jdbc.queryForObject("SELECT * FROM t_schedule_reminder WHERE user_id=? AND start_epoch=?", REMINDER, userId, epoch);
    }

    public Reminder replace(Reminder old, long termId, long version, String fingerprint, String keys, Long notification) {
        jdbc.update("""
                UPDATE t_schedule_reminder SET term_id=?,schedule_version=?,group_fingerprint=?,member_keys=?,
                  active=1,notification_id=? WHERE user_id=? AND reminder_id=?
                """, termId, version, fingerprint, keys, notification, old.userId(), old.id());
        return new Reminder(old.id(), old.userId(), termId, old.startEpoch(), version, fingerprint, true, notification);
    }

    public void cancel(Reminder row, Long keepNotification) {
        jdbc.update("UPDATE t_schedule_reminder SET active=0,notification_id=? WHERE user_id=? AND reminder_id=?",
                keepNotification, row.userId(), row.id());
    }

    public void attachNotification(Reminder row, long notificationId) {
        jdbc.update("UPDATE t_schedule_reminder SET notification_id=? WHERE user_id=? AND reminder_id=? AND notification_id IS NULL",
                notificationId, row.userId(), row.id());
    }

    public void prune(long before) {
        jdbc.update("DELETE FROM t_schedule_reminder WHERE start_epoch<? ORDER BY start_epoch LIMIT 1000", before);
    }

    public void purge(long userId) {
        jdbc.update("DELETE FROM t_schedule_reminder WHERE user_id=?", userId);
        jdbc.update("DELETE FROM t_schedule_reminder_pref WHERE user_id=?", userId);
    }
}
