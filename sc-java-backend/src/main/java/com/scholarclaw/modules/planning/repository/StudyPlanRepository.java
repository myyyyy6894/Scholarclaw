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

/** 仅操作 planning 自有表；每个资源查询都限定 owner。 */
@Repository
public class StudyPlanRepository {
    public record Plan(long id, long userId, String requestId, String requestFingerprint, LocalDate from, LocalDate to,
                       String timezone, String sourceFingerprint, String occupancyFingerprint, String state, long revision,
                       String warningsJson, String shortagesJson, String unavailableJson, String acceptRequestId,
                       String acceptFingerprint, LocalDateTime createdAt) { }
    public record Block(long id, long planId, long userId, long taskId, String title, LocalDateTime startUtc,
                        LocalDateTime endUtc, LocalDate localDate, String state, boolean locked, boolean overdue, long version) { }
    private static final RowMapper<Plan> PLAN = (r, n) -> new Plan(r.getLong("plan_id"), r.getLong("user_id"),
            r.getString("client_request_id"), r.getString("request_fingerprint"), r.getObject("from_date", LocalDate.class),
            r.getObject("to_date", LocalDate.class), r.getString("timezone"), r.getString("source_fingerprint"),
            r.getString("occupancy_fingerprint"), r.getString("state"), r.getLong("revision"), r.getString("warnings_json"),
            r.getString("shortages_json"), r.getString("unavailable_json"), r.getString("accept_request_id"),
            r.getString("accept_fingerprint"), r.getObject("created_at", LocalDateTime.class));
    private static final RowMapper<Block> BLOCK = (r, n) -> new Block(r.getLong("block_id"), r.getLong("plan_id"),
            r.getLong("user_id"), r.getLong("task_id"), r.getString("task_title"),
            r.getObject("start_utc", LocalDateTime.class), r.getObject("end_utc", LocalDateTime.class),
            r.getObject("local_date", LocalDate.class), r.getString("state"), r.getBoolean("locked"),
            r.getBoolean("overdue"), r.getLong("version"));
    private final JdbcTemplate jdbc;
    public StudyPlanRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<Plan> plan(long userId, long id) {
        return jdbc.query("SELECT * FROM t_study_plan WHERE user_id=? AND plan_id=?", PLAN, userId, id).stream().findFirst();
    }
    public Optional<Plan> byRequest(long userId, String requestId) {
        return jdbc.query("SELECT * FROM t_study_plan WHERE user_id=? AND client_request_id=?", PLAN, userId, requestId).stream().findFirst();
    }
    public Optional<Plan> byAcceptRequest(long userId, String requestId) {
        return jdbc.query("SELECT * FROM t_study_plan WHERE user_id=? AND accept_request_id=?", PLAN, userId, requestId).stream().findFirst();
    }
    public List<Plan> recent(long userId) {
        return jdbc.query("SELECT * FROM t_study_plan WHERE user_id=? ORDER BY plan_id DESC LIMIT 50", PLAN, userId);
    }
    public int draftCount(long userId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM t_study_plan WHERE user_id=? AND state='DRAFT'", Integer.class, userId);
        return count == null ? 0 : count;
    }
    public long insertPlan(Plan p, LocalDateTime now) {
        return insert("INSERT INTO t_study_plan (user_id,client_request_id,request_fingerprint,from_date,to_date,timezone,source_fingerprint,occupancy_fingerprint,state,warnings_json,shortages_json,unavailable_json,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                p.userId(), p.requestId(), p.requestFingerprint(), p.from(), p.to(), p.timezone(), p.sourceFingerprint(),
                p.occupancyFingerprint(), "DRAFT", p.warningsJson(), p.shortagesJson(), p.unavailableJson(), now, now);
    }
    public long insertBlock(Block b, LocalDateTime now) {
        return insert("INSERT INTO t_study_plan_block (plan_id,user_id,task_id,task_title,start_utc,end_utc,local_date,state,locked,overdue,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                b.planId(), b.userId(), b.taskId(), b.title(), b.startUtc(), b.endUtc(), b.localDate(), "PROPOSED", false,
                b.overdue(), now, now);
    }
    public List<Block> blocks(long userId, long planId) {
        return jdbc.query("SELECT * FROM t_study_plan_block WHERE user_id=? AND plan_id=? ORDER BY start_utc,block_id", BLOCK, userId, planId);
    }
    public Optional<Block> block(long userId, long planId, long blockId) {
        return jdbc.query("SELECT * FROM t_study_plan_block WHERE user_id=? AND plan_id=? AND block_id=?", BLOCK,
                userId, planId, blockId).stream().findFirst();
    }
    public List<Block> futurePlanned(long userId, LocalDateTime nowUtc) {
        return jdbc.query("SELECT * FROM t_study_plan_block WHERE user_id=? AND state='PLANNED' AND end_utc>? ORDER BY block_id",
                BLOCK, userId, nowUtc);
    }
    public List<Block> doneBetween(long userId, LocalDateTime fromUtc, LocalDateTime toUtc) {
        return jdbc.query("SELECT * FROM t_study_plan_block WHERE user_id=? AND state='DONE' AND start_utc<? AND end_utc>? ORDER BY block_id",
                BLOCK, userId, toUtc, fromUtc);
    }
    public int acceptPlan(long userId, long planId, long revision, String requestId, String fingerprint, LocalDateTime now) {
        return jdbc.update("UPDATE t_study_plan SET state='ACCEPTED',revision=revision+1,accept_request_id=?,accept_fingerprint=?,updated_at=? WHERE user_id=? AND plan_id=? AND state='DRAFT' AND revision=?",
                requestId, fingerprint, now, userId, planId, revision);
    }
    public int setBlockState(long userId, long planId, long blockId, long version, String state, LocalDateTime now) {
        return jdbc.update("UPDATE t_study_plan_block SET state=?,version=version+1,updated_at=? WHERE user_id=? AND plan_id=? AND block_id=? AND version=?",
                state, now, userId, planId, blockId, version);
    }
    public int moveBlock(long userId, long planId, long blockId, long version, LocalDateTime startUtc,
                         LocalDateTime endUtc, LocalDate localDate, boolean overdue, LocalDateTime now) {
        return jdbc.update("UPDATE t_study_plan_block SET start_utc=?,end_utc=?,local_date=?,overdue=?,version=version+1,updated_at=? WHERE user_id=? AND plan_id=? AND block_id=? AND version=? AND state='PLANNED'",
                startUtc, endUtc, localDate, overdue, now, userId, planId, blockId, version);
    }
    public int lockBlock(long userId, long planId, long blockId, long version, boolean locked, LocalDateTime now) {
        return jdbc.update("UPDATE t_study_plan_block SET locked=?,version=version+1,updated_at=? WHERE user_id=? AND plan_id=? AND block_id=? AND version=? AND state='PLANNED'",
                locked, now, userId, planId, blockId, version);
    }
    public int revisePlan(long userId, long planId, long revision, String sourceFingerprint, String warningsJson,
                          LocalDateTime now) {
        return jdbc.update("UPDATE t_study_plan SET revision=revision+1,source_fingerprint=COALESCE(?,source_fingerprint),warnings_json=COALESCE(?,warnings_json),updated_at=? WHERE user_id=? AND plan_id=? AND revision=? AND state='ACCEPTED'",
                sourceFingerprint, warningsJson, now, userId, planId, revision);
    }
    /** DDL 取消事件在同一事务内调用；取消未来块但不触碰已完成或已经开始的记录。 */
    public void cancelFutureForTask(long userId, long taskId, LocalDateTime nowUtc, LocalDateTime updated) {
        List<Long> planIds = jdbc.queryForList("SELECT plan_id FROM t_study_plan_block WHERE user_id=? AND task_id=? AND state='PLANNED' AND start_utc>? FOR UPDATE",
                Long.class, userId, taskId, nowUtc).stream().distinct().toList();
        jdbc.update("UPDATE t_study_plan_block SET state='CANCELED',version=version+1,updated_at=? WHERE user_id=? AND task_id=? AND state='PLANNED' AND start_utc>?",
                updated, userId, taskId, nowUtc);
        for (long planId : planIds) {
            jdbc.update("UPDATE t_study_plan SET revision=revision+1,updated_at=? WHERE user_id=? AND plan_id=? AND state='ACCEPTED'",
                    updated, userId, planId);
        }
    }
    public int cancelPlan(long userId, long planId, long revision, LocalDateTime now) {
        return jdbc.update("UPDATE t_study_plan SET state='CANCELED',revision=revision+1,updated_at=? WHERE user_id=? AND plan_id=? AND state='DRAFT' AND revision=?",
                now, userId, planId, revision);
    }
    public void cancelProposals(long userId, long planId, LocalDateTime now) {
        jdbc.update("UPDATE t_study_plan_block SET state='CANCELED',version=version+1,updated_at=? WHERE user_id=? AND plan_id=? AND state='PROPOSED'",
                now, userId, planId);
    }
    public void deleteUserData(long userId) {
        jdbc.update("DELETE FROM t_study_plan_block WHERE user_id=?", userId);
        jdbc.update("DELETE FROM t_study_plan WHERE user_id=?", userId);
    }
    private long insert(String sql, Object... values) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            return statement;
        }, keys);
        Number id = keys.getKey();
        if (id == null) throw new IllegalStateException("计划数据未返回 ID");
        return id.longValue();
    }
}
