package com.scholarclaw.modules.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.scholarclaw.modules.knowledge.entity.VectorCleanupOutboxEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@code t_vector_cleanup_outbox} 的唯一 Mapper（{@code 03} §6.0：本表唯一写入模块 = `knowledge`）。
 */
@Mapper
public interface VectorCleanupOutboxMapper extends BaseMapper<VectorCleanupOutboxEntity> {
}
