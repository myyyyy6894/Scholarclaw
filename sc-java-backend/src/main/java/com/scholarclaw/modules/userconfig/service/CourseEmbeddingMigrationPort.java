package com.scholarclaw.modules.userconfig.service;

/**
 * 已有课程的 Embedding 批量迁移端口（TASK-506 复核修复）。
 *
 * <p>`userconfig` 是 `t_user_config` 的 Single-Writer，但不能直接改 `t_course_namespace`；
 * 由 `course` 模块实现本端口。调用方必须已开启事务：用户默认 Key 的覆盖与课程批量切换
 * 必须同成同败，否则旧课程会失去可解密的 Key。
 */
public interface CourseEmbeddingMigrationPort {

    /**
     * 将用户全部活动课程切换到新的 Embedding 目标并触发重建。
     *
     * @return 实际迁移的课程数
     */
    int migrateExistingCourses(Long userId, String provider, String model, int dim);
}
