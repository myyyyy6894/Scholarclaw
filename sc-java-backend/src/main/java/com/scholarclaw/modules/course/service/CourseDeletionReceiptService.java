package com.scholarclaw.modules.course.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.scholarclaw.common.config.ScTimeZone;
import com.scholarclaw.modules.course.entity.CourseDeletionReceiptEntity;
import com.scholarclaw.modules.course.mapper.CourseDeletionReceiptMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 课程删除回执/墓碑（{@code 03} §4.11、P9 复核第 4 项）。
 *
 * <p>课程行在第二阶段物理删除后，删除接口仍要满足幂等契约：同一用户重复删除返回
 * `200` 与原结果，其他用户仍返回 `404`。回执只记录归属与结果，不保存课程内容。
 */
@Service
public class CourseDeletionReceiptService {

    public static final String STATUS_DELETING = "DELETING";
    public static final String STATUS_DELETED = "DELETED";

    private final CourseDeletionReceiptMapper mapper;

    public CourseDeletionReceiptService(CourseDeletionReceiptMapper mapper) {
        this.mapper = mapper;
    }

    public boolean exists(Long userId, Long courseId) {
        if (userId == null || courseId == null) {
            return false;
        }
        Long count = mapper.selectCount(Wrappers.<CourseDeletionReceiptEntity>lambdaQuery()
                .eq(CourseDeletionReceiptEntity::getUserId, userId)
                .eq(CourseDeletionReceiptEntity::getCourseId, courseId));
        return count != null && count > 0;
    }

    /** 软删事务内写回执；并发重复删除由唯一索引兜底，重复视为成功。 */
    @Transactional(rollbackFor = Exception.class)
    public void recordDeleting(Long userId, Long courseId) {
        CourseDeletionReceiptEntity entity = new CourseDeletionReceiptEntity();
        entity.setUserId(userId);
        entity.setCourseId(courseId);
        entity.setResultStatus(STATUS_DELETING);
        entity.setCreatedAt(ScTimeZone.now().toLocalDateTime());
        try {
            mapper.insert(entity);
        } catch (DuplicateKeyException e) {
            // 并发删除已经写入回执：保持原记录，不覆盖创建时间。
        }
    }

    /** 课程行物理删除后把回执推进为最终态；重复调用幂等。 */
    @Transactional(rollbackFor = Exception.class)
    public void markDeleted(Long userId, Long courseId) {
        mapper.update(null, Wrappers.<CourseDeletionReceiptEntity>lambdaUpdate()
                .eq(CourseDeletionReceiptEntity::getUserId, userId)
                .eq(CourseDeletionReceiptEntity::getCourseId, courseId)
                .set(CourseDeletionReceiptEntity::getResultStatus, STATUS_DELETED));
    }

    /** 账号注销：物理删除该用户的全部课程回执（回执也属于用户数据）。 */
    @Transactional(rollbackFor = Exception.class)
    public int deleteByUser(Long userId) {
        if (userId == null) {
            return 0;
        }
        return mapper.delete(Wrappers.<CourseDeletionReceiptEntity>lambdaQuery()
                .eq(CourseDeletionReceiptEntity::getUserId, userId));
    }
}
