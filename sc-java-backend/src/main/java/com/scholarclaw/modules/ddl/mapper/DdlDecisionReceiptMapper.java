package com.scholarclaw.modules.ddl.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.scholarclaw.modules.ddl.entity.DdlDecisionReceiptEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * {@code t_ddl_decision_receipt} 的唯一 Mapper（`ddl` 模块 Single-Writer）。
 *
 * <p>回执查询强制带 {@code user_id}，不复用全局唯一键回查后由上层复核归属的写法。
 */
@Mapper
public interface DdlDecisionReceiptMapper extends BaseMapper<DdlDecisionReceiptEntity> {

    @Select("""
            SELECT * FROM t_ddl_decision_receipt
             WHERE user_id = #{userId}
               AND client_request_id = #{clientRequestId}
             LIMIT 1
            """)
    DdlDecisionReceiptEntity selectByRequest(@Param("userId") Long userId,
                                             @Param("clientRequestId") String clientRequestId);
}
