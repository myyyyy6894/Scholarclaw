package com.scholarclaw.modules.schedule.repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** Schedule-owned import tasks; all public resource reads are scoped to the owner. */
@Repository
public class ScheduleImportRepository {
    public record ImportRow(long id, long userId, long termId, String requestId, String fingerprint,
                            String sourceType, String sourceText, String sourcePath, String sourceMediaType,
                            String modelFingerprint,
                            String status, long scheduleVersion, String draftJson, String errorMessage,
                            int attempt, String leaseToken, LocalDateTime leaseUntil, long version,
                            LocalDateTime expiresAt, String confirmRequestId, String confirmFingerprint,
                            String receiptJson, String sourceFormat, boolean fileAssistUsed) { }
    private static final RowMapper<ImportRow> ROW = (rs, n) -> new ImportRow(
            rs.getLong("import_id"), rs.getLong("user_id"), rs.getLong("term_id"),
            rs.getString("client_request_id"), rs.getString("input_fingerprint"), rs.getString("source_type"),
            rs.getString("source_text"), rs.getString("source_path"), rs.getString("source_media_type"),
            rs.getString("model_fingerprint"),
            rs.getString("status"), rs.getLong("schedule_version"), rs.getString("draft_json"),
            rs.getString("error_message"), rs.getInt("attempt"), rs.getString("lease_token"),
            rs.getObject("lease_until", LocalDateTime.class), rs.getLong("version"),
            rs.getObject("expires_at", LocalDateTime.class), rs.getString("confirm_request_id"),
            rs.getString("confirm_fingerprint"), rs.getString("receipt_json"), rs.getString("source_format"), rs.getBoolean("file_assist_used"));
    private final JdbcTemplate jdbc;

    public ScheduleImportRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public long insertText(long userId, long termId, String requestId, String fingerprint, String text,
                           long scheduleVersion, LocalDateTime expiresAt, LocalDateTime now) {
        var holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("INSERT INTO t_schedule_import "
                    + "(user_id,term_id,client_request_id,input_fingerprint,source_type,source_text,status,"
                    + "schedule_version,expires_at,created_at,updated_at) VALUES (?,?,?,?,'TEXT',?,'QUEUED',?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, userId); ps.setLong(2, termId); ps.setString(3, requestId); ps.setString(4, fingerprint);
            ps.setString(5, text); ps.setLong(6, scheduleVersion); ps.setObject(7, expiresAt);
            ps.setObject(8, now); ps.setObject(9, now);
            return ps;
        }, holder);
        Number id = holder.getKey();
        if (id == null) throw new IllegalStateException("导入任务未返回 ID");
        return id.longValue();
    }
    public long insertImage(long userId, long termId, String requestId, String fingerprint, String path,
                            String mediaType, String modelFingerprint, long scheduleVersion,
                            LocalDateTime expiresAt, LocalDateTime now) {
        var holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("INSERT INTO t_schedule_import "
                    + "(user_id,term_id,client_request_id,input_fingerprint,source_type,source_path,source_media_type,"
                    + "model_fingerprint,status,schedule_version,expires_at,created_at,updated_at) "
                    + "VALUES (?,?,?,?,'IMAGE',?,?,?,'QUEUED',?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, userId); ps.setLong(2, termId); ps.setString(3, requestId); ps.setString(4, fingerprint);
            ps.setString(5, path); ps.setString(6, mediaType); ps.setString(7, modelFingerprint);
            ps.setLong(8, scheduleVersion); ps.setObject(9, expiresAt); ps.setObject(10, now); ps.setObject(11, now);
            return ps;
        }, holder);
        Number id = holder.getKey();
        if (id == null) throw new IllegalStateException("导入任务未返回 ID");
        return id.longValue();
    }
    public long insertFile(long userId, long termId, String requestId, String fingerprint, String format,
                           String draft, long scheduleVersion, LocalDateTime expiresAt, LocalDateTime now) {
        var holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO t_schedule_import
                      (user_id,term_id,client_request_id,input_fingerprint,source_type,source_format,status,
                       draft_json,schedule_version,expires_at,created_at,updated_at)
                    VALUES (?,?,?,?,'FILE',?,'REVIEW_REQUIRED',?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, userId);
            ps.setLong(2, termId);
            ps.setString(3, requestId);
            ps.setString(4, fingerprint);
            ps.setString(5, format);
            ps.setString(6, draft);
            ps.setLong(7, scheduleVersion);
            ps.setObject(8, expiresAt);
            ps.setObject(9, now);
            ps.setObject(10, now);
            return ps;
        }, holder);
        Number id = holder.getKey();
        if (id == null) throw new IllegalStateException("导入任务未返回 ID");
        return id.longValue();
    }

    public int replaceFileLayout(long userId, long id, long version, String draft, LocalDateTime now) {
        return jdbc.update("""
                UPDATE t_schedule_import SET draft_json=?,error_message=NULL,version=version+1,updated_at=?
                WHERE user_id=? AND import_id=? AND version=? AND source_type='FILE' AND status='REVIEW_REQUIRED'
                  AND expires_at>?
                """, draft, now, userId, id, version, now);
    }

    public int consumeFileAssist(long userId, long id, long version, LocalDateTime now) {
        return jdbc.update("""
                UPDATE t_schedule_import SET file_assist_used=1,version=version+1,updated_at=?
                WHERE user_id=? AND import_id=? AND version=? AND source_type='FILE' AND status='REVIEW_REQUIRED'
                  AND file_assist_used=0 AND expires_at>?
                """, now, userId, id, version, now);
    }

    public void finishFileAssist(long userId, long id, long version, String draft, String error, LocalDateTime now) {
        jdbc.update("""
                UPDATE t_schedule_import SET draft_json=COALESCE(?,draft_json),error_message=?,
                  version=version+1,updated_at=?
                WHERE user_id=? AND import_id=? AND version=? AND source_type='FILE' AND status='REVIEW_REQUIRED'
                  AND file_assist_used=1 AND expires_at>?
                """, draft, error, now, userId, id, version, now);
    }

    public Optional<ImportRow> find(long userId, long id) {
        return jdbc.query("SELECT * FROM t_schedule_import WHERE user_id=? AND import_id=?", ROW, userId, id)
                .stream().findFirst();
    }
    public List<ImportRow> recent(long userId, long termId) {
        return jdbc.query("SELECT * FROM t_schedule_import WHERE user_id=? AND term_id=? ORDER BY import_id DESC LIMIT 20",
                ROW, userId, termId);
    }
    public Optional<ImportRow> findForUpdate(long userId, long id) {
        return jdbc.query("SELECT * FROM t_schedule_import WHERE user_id=? AND import_id=? FOR UPDATE", ROW, userId, id)
                .stream().findFirst();
    }
    public Optional<ImportRow> byRequest(long userId, String requestId) {
        return jdbc.query("SELECT * FROM t_schedule_import WHERE user_id=? AND client_request_id=?", ROW, userId, requestId)
                .stream().findFirst();
    }
    public Optional<ImportRow> byConfirmRequest(long userId, String requestId) {
        return jdbc.query("SELECT * FROM t_schedule_import WHERE user_id=? AND confirm_request_id=?", ROW, userId, requestId)
                .stream().findFirst();
    }
    public long activeCount(long userId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM t_schedule_import WHERE user_id=? "
                + "AND status IN ('QUEUED','RUNNING','REVIEW_REQUIRED','FAILED')", Long.class, userId);
        return count == null ? 0 : count;
    }
    public List<Long> candidates(LocalDateTime now, int limit) {
        return jdbc.queryForList("SELECT import_id FROM t_schedule_import WHERE source_type='TEXT' AND expires_at>? "
                        + "AND attempt<3 AND (status='QUEUED' OR (status='RUNNING' AND lease_until<=?)) "
                        + "ORDER BY import_id LIMIT ?", Long.class, now, now, limit);
    }
    public boolean claim(long id, String token, LocalDateTime now, LocalDateTime until) {
        return jdbc.update("UPDATE t_schedule_import SET status='RUNNING',attempt=attempt+1,lease_token=?,lease_until=?,"
                        + "error_message=NULL,version=version+1,updated_at=? WHERE import_id=? AND source_type='TEXT' "
                        + "AND expires_at>? AND attempt<3 AND (status='QUEUED' OR (status='RUNNING' AND lease_until<=?))",
                token, until, now, id, now, now) == 1;
    }
    public void unclaim(long id, String token, LocalDateTime now) {
        jdbc.update("UPDATE t_schedule_import SET status='QUEUED',attempt=attempt-1,lease_token=NULL,lease_until=NULL,"
                + "version=version+1,updated_at=? WHERE import_id=? AND status='RUNNING' AND lease_token=?",
                now, id, token);
    }
    public Optional<ImportRow> claimed(long id, String token) {
        return jdbc.query("SELECT * FROM t_schedule_import WHERE import_id=? AND status='RUNNING' AND lease_token=?",
                ROW, id, token).stream().findFirst();
    }
    public boolean finish(long id, String token, String draftJson, LocalDateTime now) {
        return jdbc.update("UPDATE t_schedule_import SET status='REVIEW_REQUIRED',draft_json=?,lease_token=NULL,"
                        + "lease_until=NULL,version=version+1,updated_at=? WHERE import_id=? AND status='RUNNING' "
                        + "AND lease_token=? AND expires_at>?", draftJson, now, id, token, now) == 1;
    }
    public boolean fail(long id, String token, String message, LocalDateTime now) {
        return jdbc.update("UPDATE t_schedule_import SET status='FAILED',error_message=?,lease_token=NULL,"
                        + "lease_until=NULL,version=version+1,updated_at=? WHERE import_id=? AND status='RUNNING' "
                        + "AND lease_token=? AND expires_at>?", message, now, id, token, now) == 1;
    }
    public int failExhaustedLeases(LocalDateTime now) {
        return jdbc.update("UPDATE t_schedule_import SET status='FAILED',error_message=?,lease_token=NULL,lease_until=NULL,"
                        + "version=version+1,updated_at=? WHERE status='RUNNING' AND source_type='TEXT' "
                        + "AND attempt>=3 AND lease_until<=? AND expires_at>? ORDER BY import_id LIMIT 100",
                "解析多次中断，请新建导入任务或改用手动维护。", now, now, now);
    }
    public int expireTextTasks(LocalDateTime now) {
        return jdbc.update("UPDATE t_schedule_import SET status='EXPIRED',source_text=NULL,draft_json=NULL,"
                        + "lease_token=NULL,lease_until=NULL,error_message=NULL,version=version+1,updated_at=? "
                        + "WHERE source_type IN ('TEXT','FILE') AND status IN ('QUEUED','RUNNING','REVIEW_REQUIRED','FAILED') "
                        + "AND expires_at<=? ORDER BY import_id LIMIT 100", now, now);
    }
    public int retry(long userId, long id, long version, LocalDateTime now) {
        return jdbc.update("UPDATE t_schedule_import SET status='QUEUED',error_message=NULL,version=version+1,updated_at=? "
                        + "WHERE user_id=? AND import_id=? AND version=? AND source_type='TEXT' "
                        + "AND status='FAILED' AND attempt<3 AND expires_at>?",
                now, userId, id, version, now);
    }
    public List<Long> imageCandidates(LocalDateTime now, int limit) {
        return jdbc.queryForList("SELECT import_id FROM t_schedule_import WHERE source_type='IMAGE' AND status='QUEUED' "
                + "AND attempt<3 AND expires_at>? ORDER BY import_id LIMIT ?", Long.class, now, limit);
    }
    public boolean claimImage(long id, String token, LocalDateTime now, LocalDateTime until) {
        return jdbc.update("UPDATE t_schedule_import SET status='RUNNING',attempt=attempt+1,lease_token=?,lease_until=?,"
                        + "error_message=NULL,version=version+1,updated_at=? WHERE import_id=? AND source_type='IMAGE' "
                        + "AND status='QUEUED' AND attempt<3 AND expires_at>?", token, until, now, id, now) == 1;
    }
    public boolean imageNeedsManualReview(long id, String token, String message, LocalDateTime now) {
        return jdbc.update("UPDATE t_schedule_import SET status='REVIEW_REQUIRED',draft_json=JSON_OBJECT('rows',JSON_ARRAY()),"
                        + "error_message=?,lease_token=NULL,lease_until=NULL,version=version+1,updated_at=? "
                        + "WHERE import_id=? AND source_type='IMAGE' AND status='RUNNING' AND lease_token=? AND expires_at>?",
                message, now, id, token, now) == 1;
    }
    /** A lost lease never replays a potentially billable image request. */
    public int recoverImageLeases(LocalDateTime now) {
        return jdbc.update("UPDATE t_schedule_import SET status='REVIEW_REQUIRED',draft_json=JSON_OBJECT('rows',JSON_ARRAY()),"
                        + "error_message=?,lease_token=NULL,lease_until=NULL,version=version+1,updated_at=? "
                        + "WHERE source_type='IMAGE' AND status='RUNNING' AND lease_until<=? AND expires_at>? "
                        + "ORDER BY import_id LIMIT 100",
                "图片处理已中断，可能已产生费用；可手动填写，或重新确认后再次识别。", now, now, now);
    }
    public int retryImage(long userId, long id, long version, String modelFingerprint, LocalDateTime now) {
        return jdbc.update("UPDATE t_schedule_import SET status='QUEUED',model_fingerprint=?,draft_json=NULL,"
                        + "error_message=NULL,version=version+1,updated_at=? WHERE user_id=? AND import_id=? "
                        + "AND source_type='IMAGE' AND status='REVIEW_REQUIRED' AND version=? AND attempt<3 AND expires_at>?",
                modelFingerprint, now, userId, id, version, now);
    }
    public int expireImageTasks(LocalDateTime now) {
        return jdbc.update("UPDATE t_schedule_import SET status='EXPIRED',draft_json=NULL,lease_token=NULL,lease_until=NULL,"
                        + "error_message=NULL,version=version+1,updated_at=? WHERE source_type='IMAGE' "
                        + "AND status IN ('QUEUED','RUNNING','REVIEW_REQUIRED','FAILED') AND expires_at<=? "
                        + "ORDER BY import_id LIMIT 100", now, now);
    }
    public List<ImportRow> imageCleanupCandidates(LocalDateTime retryBefore, int limit) {
        return jdbc.query("SELECT * FROM t_schedule_import WHERE source_type='IMAGE' AND source_path IS NOT NULL "
                        + "AND status IN ('CONFIRMED','CANCELED','EXPIRED') "
                        + "AND (cleanup_attempt_at IS NULL OR cleanup_attempt_at<=?) ORDER BY import_id LIMIT ?",
                ROW, retryBefore, limit);
    }
    public void clearImagePath(long id, String path, LocalDateTime now) {
        jdbc.update("UPDATE t_schedule_import SET source_path=NULL,cleanup_attempt_at=NULL,updated_at=? "
                        + "WHERE import_id=? AND source_type='IMAGE' "
                        + "AND source_path=? AND status IN ('CONFIRMED','CANCELED','EXPIRED')", now, id, path);
    }
    public void recordImageCleanupFailure(long id, String path, LocalDateTime now) {
        jdbc.update("UPDATE t_schedule_import SET cleanup_attempt_at=? WHERE import_id=? AND source_type='IMAGE' "
                        + "AND source_path=? AND status IN ('CONFIRMED','CANCELED','EXPIRED')", now, id, path);
    }
    public boolean imagePathReferenced(long userId, String path) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM t_schedule_import WHERE user_id=? AND source_type='IMAGE' "
                + "AND source_path=?", Integer.class, userId, path);
        return count != null && count > 0;
    }
    public int cancel(long userId, long id, long version, LocalDateTime now) {
        return jdbc.update("UPDATE t_schedule_import SET status='CANCELED',source_text=NULL,draft_json=NULL,"
                        + "lease_token=NULL,lease_until=NULL,error_message=NULL,version=version+1,updated_at=? "
                        + "WHERE user_id=? AND import_id=? AND version=? AND status IN ('QUEUED','RUNNING','REVIEW_REQUIRED','FAILED')",
                now, userId, id, version);
    }
    public int confirm(long userId, long id, long version, String requestId, String fingerprint,
                       String receiptJson, LocalDateTime now) {
        return jdbc.update("UPDATE t_schedule_import SET status='CONFIRMED',source_text=NULL,draft_json=NULL,"
                        + "confirm_request_id=?,confirm_fingerprint=?,receipt_json=?,lease_token=NULL,lease_until=NULL,"
                        + "error_message=NULL,version=version+1,updated_at=? WHERE user_id=? AND import_id=? "
                        + "AND version=? AND status='REVIEW_REQUIRED' AND expires_at>?",
                requestId, fingerprint, receiptJson, now, userId, id, version, now);
    }
}
