package com.scholarclaw.modules.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.scholarclaw.modules.notification.entity.NotificationLogEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
}
