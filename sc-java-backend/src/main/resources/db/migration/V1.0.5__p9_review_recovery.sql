-- V1.0.5：P9 复核修复——删除补偿 outbox 与课程删除回执。
--
-- 背景（P9 复核第 2、4 项）：
--   1. 向量清理失败时只依赖 RabbitMQ 补偿消息；若 Python/Chroma 与 RabbitMQ 同时失败，
--      业务行进入 DELETED / 课程行被物理删除后，GC 再也找不到该次清理。本表把补偿意图
--      持久化在 MySQL（与业务行同库），作为“向量清理尚未确认”的权威凭据。
--   2. 课程行按 03 §6.7 在第二阶段物理删除；删除接口需要墓碑才能在重复删除时返回原结果
--      而不是 404。本表只记录“该用户确实删除过该课程”的回执，不保存课程内容。
--
-- 单写归口：t_vector_cleanup_outbox 由 knowledge 模块写入；t_course_deletion_receipt
-- 由 course 模块写入。两表都不建物理外键（03 §3.3.1）。

CREATE TABLE IF NOT EXISTS `t_vector_cleanup_outbox` (
  `cleanup_id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '清理任务自增主键',
  `dedup_key`             VARCHAR(191) NOT NULL COMMENT '幂等去重键：scope|user|course|doc|versions',
  `scope`                 VARCHAR(32)  NOT NULL COMMENT '清理粒度：DOCUMENT / COURSE_COLLECTION / USER_ALL',
  `user_id`               BIGINT       NOT NULL COMMENT '资源归属用户 ID',
  `course_id`             BIGINT       NULL COMMENT '课程 ID（USER_ALL 为空）',
  `doc_id`                BIGINT       NULL COMMENT '文档 ID（仅 DOCUMENT 非空）',
  `course_index_version`  INT          NULL COMMENT '课程索引版本快照',
  `stale_parse_version`   INT          NULL COMMENT '单文档迟到版本清理条件',
  `status`                VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / PROCESSING / DONE / MANUAL',
  `retry_count`           INT          NOT NULL DEFAULT 0 COMMENT 'GC 重放失败次数',
  `next_retry_at`         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '下次重放时间',
  `last_error`            VARCHAR(255) NULL COMMENT '最近一次失败原因（脱敏，无密钥）',
  `trace_id`              VARCHAR(64)  NULL COMMENT '创建该补偿的链路追踪号',
  `created_at`            DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`            DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后更新时间',
  PRIMARY KEY (`cleanup_id`),
  UNIQUE KEY `uk_vector_cleanup_dedup` (`dedup_key`),
  KEY `idx_vector_cleanup_retry` (`status`, `next_retry_at`),
  KEY `idx_vector_cleanup_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='ChromaDB 向量清理持久化 outbox（TASK-902 复核）';

CREATE TABLE IF NOT EXISTS `t_course_deletion_receipt` (
  `receipt_id`    BIGINT      NOT NULL AUTO_INCREMENT COMMENT '回执自增主键',
  `user_id`       BIGINT      NOT NULL COMMENT '发起删除的用户 ID',
  `course_id`     BIGINT      NOT NULL COMMENT '已删除的课程 ID',
  `result_status` VARCHAR(16) NOT NULL DEFAULT 'DELETED' COMMENT '删除结果：DELETING / DELETED',
  `created_at`    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '回执创建时间',
  PRIMARY KEY (`receipt_id`),
  UNIQUE KEY `uk_course_deletion_receipt` (`user_id`, `course_id`),
  KEY `idx_course_deletion_receipt_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='课程删除回执/墓碑，用于删除接口幂等（TASK-902 复核）';
