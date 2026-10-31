package com.scholarclaw.modules.course.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.scholarclaw.modules.course.entity.CourseDeletionReceiptEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@code t_course_deletion_receipt} 的唯一 Mapper（{@code 03} §6.0：本表唯一写入模块 = `course`）。
 */
@Mapper
public interface CourseDeletionReceiptMapper extends BaseMapper<CourseDeletionReceiptEntity> {
}
