package com.scholarclaw.modules.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.scholarclaw.modules.notification.entity.NotificationLogEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * {@code t_notification_log} 的唯一 Mapper（{@code 03} §4.7，Single-Writer = `notification`）。
 *
 * <p>所有查询强制带 {@code user_id}；列表按 {@code created_at DESC, notification_id DESC}
 * 稳定排序，避免同一毫秒内多条通知在分页时漂移。
 */
@Mapper
public interface NotificationLogMapper extends BaseMapper<NotificationLogEntity> {

    /** 分页查询；{@code readStatus} 为空表示不过滤阅读状态。 */
    @Select("""
            <script>
            SELECT * FROM t_notification_log
             WHERE user_id = #{userId}
            <if test="readStatus != null">
               AND read_status = #{readStatus}
            </if>
             ORDER BY created_at DESC, notification_id DESC
             LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<NotificationLogEntity> selectPage(@Param("userId") Long userId,
                                           @Param("readStatus") String readStatus,
                                           @Param("offset") long offset,
                                           @Param("limit") int limit);

    /** 当前过滤条件下的总数。 */
    @Select("""
            <script>
            SELECT COUNT(*) FROM t_notification_log
             WHERE user_id = #{userId}
            <if test="readStatus != null">
               AND read_status = #{readStatus}
            </if>
            </script>
            """)
    long countByUser(@Param("userId") Long userId,
                     @Param("readStatus") String readStatus);

    /** 当前用户全部未读通知数。 */
    @Select("""
            SELECT COUNT(*) FROM t_notification_log
             WHERE user_id = #{userId}
               AND read_status = 'UNREAD'
            """)
    long countUnread(@Param("userId") Long userId);

    // ---------------------------------------------------------------------
    // TASK-804 IM 出站：扫描、DB 租约领取、成功/重试/限流顺延/降级。
    // 所有状态更新都带 scheduled_push_at = 租约值，确保只有拿到租约的调度器能改状态。
    // ---------------------------------------------------------------------

    /** 扫描到期待推送记录；命中 `idx_notif_outbound_sched (outbound_status, scheduled_push_at)`。 */
    @Select("""
            SELECT * FROM t_notification_log
             WHERE outbound_status IN ('PENDING_IM', 'RATE_LIMITED_DELAYED')
               AND scheduled_push_at IS NOT NULL
               AND scheduled_push_at <= #{now}
             ORDER BY scheduled_push_at ASC, notification_id ASC
             LIMIT #{limit}
            """)
    List<NotificationLogEntity> selectOutboundCandidates(@Param("now") LocalDateTime now,
                                                         @Param("limit") int limit);

    /**
     * DB 租约领取：把 `scheduled_push_at` 推到租约截止时间，状态暂不变。
     *
     * <p>其他实例在租约到期前不会再扫到该行；发送成功后转为终态，失败则写回重试/降级时间。
     */
    @Update("""
            UPDATE t_notification_log
               SET scheduled_push_at = #{leaseUntil}, updated_at = #{now}
             WHERE notification_id = #{notificationId}
               AND outbound_status IN ('PENDING_IM', 'RATE_LIMITED_DELAYED')
               AND scheduled_push_at = #{expectedScheduledAt}
            """)
    int claimOutbound(@Param("notificationId") Long notificationId,
                      @Param("expectedScheduledAt") LocalDateTime expectedScheduledAt,
                      @Param("leaseUntil") LocalDateTime leaseUntil,
                      @Param("now") LocalDateTime now);

    /** 推送成功：终态 `SENT_IM`。 */
    @Update("""
            UPDATE t_notification_log
               SET outbound_status = 'SENT_IM',
                   sent_im_at = #{now},
                   scheduled_push_at = NULL,
                   im_error_msg = NULL,
                   updated_at = #{now}
             WHERE notification_id = #{notificationId}
               AND outbound_status IN ('PENDING_IM', 'RATE_LIMITED_DELAYED')
               AND scheduled_push_at = #{leaseUntil}
            """)
    int markSent(@Param("notificationId") Long notificationId,
                 @Param("leaseUntil") LocalDateTime leaseUntil,
                 @Param("now") LocalDateTime now);

    /** 命中令牌桶/免打扰：保持 `RATE_LIMITED_DELAYED` 并顺延。 */
    @Update("""
            UPDATE t_notification_log
               SET outbound_status = 'RATE_LIMITED_DELAYED',
                   scheduled_push_at = #{nextPushAt},
                   im_error_msg = #{errorMsg},
                   updated_at = #{now}
             WHERE notification_id = #{notificationId}
               AND outbound_status IN ('PENDING_IM', 'RATE_LIMITED_DELAYED')
               AND scheduled_push_at = #{leaseUntil}
            """)
    int markRateLimited(@Param("notificationId") Long notificationId,
                        @Param("leaseUntil") LocalDateTime leaseUntil,
                        @Param("nextPushAt") LocalDateTime nextPushAt,
                        @Param("errorMsg") String errorMsg,
                        @Param("now") LocalDateTime now);

    /** 可重试失败：计数 +1，回到 `PENDING_IM` 并延迟重试。 */
    @Update("""
            UPDATE t_notification_log
               SET outbound_status = 'PENDING_IM',
                   im_retry_count = im_retry_count + 1,
                   scheduled_push_at = #{nextPushAt},
                   im_error_msg = #{errorMsg},
                   updated_at = #{now}
             WHERE notification_id = #{notificationId}
               AND outbound_status IN ('PENDING_IM', 'RATE_LIMITED_DELAYED')
               AND scheduled_push_at = #{leaseUntil}
               AND im_retry_count = #{expectedRetryCount}
               AND im_retry_count < 2
            """)
    int markRetry(@Param("notificationId") Long notificationId,
                  @Param("leaseUntil") LocalDateTime leaseUntil,
                  @Param("nextPushAt") LocalDateTime nextPushAt,
                  @Param("expectedRetryCount") int expectedRetryCount,
                  @Param("errorMsg") String errorMsg,
                  @Param("now") LocalDateTime now);

    /** 终态降级：不再推送 IM，站内信保底。 */
    @Update("""
            UPDATE t_notification_log
               SET outbound_status = 'FALLBACK_WEB_ONLY',
                   scheduled_push_at = NULL,
                   im_error_msg = #{errorMsg},
                   updated_at = #{now}
             WHERE notification_id = #{notificationId}
               AND outbound_status IN ('PENDING_IM', 'RATE_LIMITED_DELAYED')
               AND scheduled_push_at = #{leaseUntil}
            """)
    int markFallback(@Param("notificationId") Long notificationId,
                     @Param("leaseUntil") LocalDateTime leaseUntil,
                     @Param("errorMsg") String errorMsg,
                     @Param("now") LocalDateTime now);
}
