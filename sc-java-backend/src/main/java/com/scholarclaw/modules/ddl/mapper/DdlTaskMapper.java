package com.scholarclaw.modules.ddl.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.scholarclaw.modules.ddl.entity.DdlTaskEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * {@code t_ddl_task} 的唯一 Mapper（{@code 03} §6.0：本表唯一写入模块 = `ddl`）。
 *
 * <p>业务读写强制带 {@code user_id} 过滤（防 IDOR）；幂等键回查命中全局唯一索引后，
 * 由 Service 复核 {@code user_id} 归属。更新类操作由 Service 先
 * {@link #selectByIdForUpdate(Long, Long)} 锁定行，再在本地事务内条件更新，避免并发编辑/删除
 * 造成状态覆盖。
 */
@Mapper
public interface DdlTaskMapper extends BaseMapper<DdlTaskEntity> {

    /**
     * 请求级幂等键回查；命中 {@code uk_ddl_idempotency_key}。
     *
     * <p>幂等键全局唯一，但仍强制带 {@code user_id}：所有业务查询不得存在仅按业务键读取、
     * 依赖上层复核用户归属的路径（防御纵深）。
     */
    @Select("""
            SELECT * FROM t_ddl_task
             WHERE idempotency_key = #{idempotencyKey}
               AND user_id = #{userId}
             LIMIT 1
            """)
    DdlTaskEntity selectByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey,
                                         @Param("userId") Long userId);

    /** 按主键 + 用户回查（防 IDOR）。 */
    @Select("""
            SELECT * FROM t_ddl_task
             WHERE task_id = #{taskId}
               AND user_id = #{userId}
             LIMIT 1
            """)
    DdlTaskEntity selectByTaskIdAndUserId(@Param("taskId") Long taskId,
                                          @Param("userId") Long userId);

    /**
     * 事务内锁定任务行（{@code SELECT ... FOR UPDATE}）。
     *
     * <p>跨用户查询不会命中；调用方必须已开启事务。
     */
    @Select("""
            SELECT * FROM t_ddl_task
             WHERE task_id = #{taskId}
               AND user_id = #{userId}
             FOR UPDATE
            """)
    DdlTaskEntity selectByIdForUpdate(@Param("taskId") Long taskId,
                                      @Param("userId") Long userId);

    /** 删除课程时解绑关联任务：保留 {@code course_name} 文本，仅把 {@code course_id} 置空。 */
    @Update("""
            UPDATE t_ddl_task
               SET course_id = NULL, updated_at = NOW(3)
             WHERE course_id = #{courseId}
               AND user_id = #{userId}
            """)
    int unbindCourse(@Param("userId") Long userId, @Param("courseId") Long courseId);

    /** 删除课程前收集关联任务 ID，用于事务提交后清理这些任务的 Redis 变更草案。 */
    @Select("""
            SELECT task_id FROM t_ddl_task
             WHERE course_id = #{courseId}
               AND user_id = #{userId}
            """)
    java.util.List<Long> selectTaskIdsByCourse(@Param("userId") Long userId,
                                               @Param("courseId") Long courseId);

    // ---------------------------------------------------------------------
    // TASK-801 定时提醒扫描与原子领取。
    // 所有“领取”更新都带状态 + 旧值条件，只有 affected_rows == 1 的调度才允许
    // 生成通知；这是“同一任务、同一梯度只提醒一次”的最终保障。
    // ---------------------------------------------------------------------

    /**
     * 扫描未来 24 小时内到期、且尚未完成全部正式梯度的 {@code ACTIVE} 任务。
     *
     * <p>保留“已过 due_at”的任务用于流转 {@code OVERDUE}；已触发 T-24h/T-6h 且尚未到期的任务
     * 不再重复扫描。命中 {@code idx_ddl_status_due (status, due_at)}。
     */
    @Select("""
            SELECT * FROM t_ddl_task
             WHERE status = 'ACTIVE'
               AND due_at IS NOT NULL
               AND due_at <= #{horizon}
               AND (due_at <= #{now} OR (reminded_flags & 3) <> 3)
             ORDER BY due_at ASC
             LIMIT #{limit}
            """)
    List<DdlTaskEntity> selectActiveReminderCandidates(@Param("now") LocalDateTime now,
                                                       @Param("horizon") LocalDateTime horizon,
                                                       @Param("limit") int limit);

    /**
     * 扫描跟催到期的待确认任务；命中 {@code idx_ddl_status_followup (status, follow_up_at)}。
     */
    @Select("""
            SELECT * FROM t_ddl_task
             WHERE status IN ('PENDING_TIME', 'PENDING_CONFIRM')
               AND follow_up_at IS NOT NULL
               AND follow_up_at <= #{now}
               AND follow_up_count < 2
             ORDER BY follow_up_at ASC
             LIMIT #{limit}
            """)
    List<DdlTaskEntity> selectFollowUpCandidates(@Param("now") LocalDateTime now,
                                                 @Param("limit") int limit);

    /** 领取 T-24h 提醒：仅当任务仍为 ACTIVE、due_at 未变且位 1 未置位时成功。 */
    @Update("""
            UPDATE t_ddl_task
               SET reminded_flags = reminded_flags | 1, updated_at = #{now}
             WHERE task_id = #{taskId}
               AND user_id = #{userId}
               AND status = 'ACTIVE'
               AND due_at = #{dueAt}
               AND (reminded_flags & 1) = 0
            """)
    int markT24Reminded(@Param("taskId") Long taskId,
                        @Param("userId") Long userId,
                        @Param("dueAt") LocalDateTime dueAt,
                        @Param("now") LocalDateTime now);

    /**
     * 领取 T-6h 提醒，并把已被本次提醒覆盖的 T-24h 位一并置位。
     *
     * <p>任务创建时距离截止不足 24 小时（或在调度停机期间同时跨过两个阈值）时，
     * 只发送更紧急的 T-6h 一条；静默补记 T-24h 位，避免下一轮再补发一条已过时的 T-24h。
     */
    @Update("""
            UPDATE t_ddl_task
               SET reminded_flags = reminded_flags | 3, updated_at = #{now}
             WHERE task_id = #{taskId}
               AND user_id = #{userId}
               AND status = 'ACTIVE'
               AND due_at = #{dueAt}
               AND (reminded_flags & 2) = 0
            """)
    int markT6Reminded(@Param("taskId") Long taskId,
                       @Param("userId") Long userId,
                       @Param("dueAt") LocalDateTime dueAt,
                       @Param("now") LocalDateTime now);

    /**
     * 领取逾期提醒并原子流转 {@code ACTIVE -> OVERDUE}。
     *
     * <p>同时置位 1/2/4：逾期提醒已覆盖此前所有正式梯度；若用户之后把任务延期到未来，
     * {@code DdlTaskService} 会按新的剩余时长重新计算并清除不适用的提醒位。
     * {@code OVERDUE} 状态必须清空 {@code follow_up_at}（{@code chk_ddl_time_invariants}）。
     */
    @Update("""
            UPDATE t_ddl_task
               SET status = 'OVERDUE',
                   reminded_flags = reminded_flags | 7,
                   follow_up_at = NULL,
                   updated_at = #{now}
             WHERE task_id = #{taskId}
               AND user_id = #{userId}
               AND status = 'ACTIVE'
               AND due_at = #{dueAt}
               AND due_at <= #{now}
               AND (reminded_flags & 4) = 0
            """)
    int markOverdueReminded(@Param("taskId") Long taskId,
                            @Param("userId") Long userId,
                            @Param("dueAt") LocalDateTime dueAt,
                            @Param("now") LocalDateTime now);

    /**
     * 领取待确认跟进提醒：按旧 {@code follow_up_at + follow_up_count} 做 CAS，
     * 防止同一关注册任务被并发调度重复计数。
     */
    @Update("""
            UPDATE t_ddl_task
               SET follow_up_count = follow_up_count + 1,
                   follow_up_at = #{nextFollowUpAt},
                   updated_at = #{now}
             WHERE task_id = #{taskId}
               AND user_id = #{userId}
               AND status IN ('PENDING_TIME', 'PENDING_CONFIRM')
               AND follow_up_at = #{expectedFollowUpAt}
               AND follow_up_at <= #{now}
               AND follow_up_count = #{expectedCount}
               AND follow_up_count < 2
            """)
    int markFollowUpReminded(@Param("taskId") Long taskId,
                             @Param("userId") Long userId,
                             @Param("expectedFollowUpAt") LocalDateTime expectedFollowUpAt,
                             @Param("expectedCount") int expectedCount,
                             @Param("nextFollowUpAt") LocalDateTime nextFollowUpAt,
                             @Param("now") LocalDateTime now);
}
