package com.scholarclaw.modules.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.scholarclaw.modules.agent.entity.ChatMessageEntity;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** {@code t_chat_message} 的唯一 Mapper（Single-Writer = `agent`）。 */
@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessageEntity> {

    /**
     * 游标分页（`API-CHAT-001`）与最近窗口共用：
     * 按 `message_id DESC` 取 `limit` 条；传入 `beforeMessageId` 时只取更早的消息。
     */
    @Select("""
            <script>
            SELECT * FROM t_chat_message
             WHERE user_id = #{userId} AND course_id = #{courseId}
             <if test="beforeMessageId != null">
               AND message_id &lt; #{beforeMessageId}
             </if>
             ORDER BY message_id DESC
             LIMIT #{limit}
            </script>
            """)
    List<ChatMessageEntity> selectPage(@Param("userId") Long userId,
                                       @Param("courseId") Long courseId,
                                       @Param("beforeMessageId") Long beforeMessageId,
                                       @Param("limit") int limit);

    @Delete("DELETE FROM t_chat_message WHERE user_id = #{userId} AND course_id = #{courseId}")
    int deleteByCourse(@Param("userId") Long userId, @Param("courseId") Long courseId);

    /** 账号注销：物理删除用户全部会话（TASK-901）。 */
    @Delete("DELETE FROM t_chat_message WHERE user_id = #{userId}")
    int deleteByUser(@Param("userId") Long userId);
}
