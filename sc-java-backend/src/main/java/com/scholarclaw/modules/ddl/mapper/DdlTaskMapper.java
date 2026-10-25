package com.scholarclaw.modules.ddl.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.scholarclaw.modules.ddl.entity.DdlTaskEntity;
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

    /** 请求级幂等键回查；命中 {@code uk_ddl_idempotency_key}。 */
    @Select("SELECT * FROM t_ddl_task WHERE idempotency_key = #{idempotencyKey} LIMIT 1")
    DdlTaskEntity selectByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

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
}
