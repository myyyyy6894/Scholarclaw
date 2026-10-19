package com.scholarclaw.modules.userconfig.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@code t_user_config} 的唯一 Mapper（{@code 03} §6.0：本表唯一写入模块 = `userconfig`）。
 *
 * <p><b>只继承 {@link BaseMapper} 的按主键与按条件查询/插入</b>；不提供通用的"整行更新"入口。
 * 组 A 与组 B 的更新必须写成两个显式 SQL 方法（{@code 03} §4.1 专项约束 1：
 * 禁止 `updateById` 整行覆盖，否则会出现"保存模型配置把密码哈希覆盖掉"这类事故）。
 * TASK-202 只需要插入（注册）与按用户名/主键查询；TASK-203 再加入组 B 的显式更新方法。
 */
@Mapper
public interface UserConfigMapper extends BaseMapper<UserConfigEntity> {
}
