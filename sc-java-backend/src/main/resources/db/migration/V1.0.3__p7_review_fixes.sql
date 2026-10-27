-- V1.0.3：P7 第三轮审阅修复（TASK-703/704 状态机、幂等指纹与决策回执）。
--
-- 1. t_ddl_task 增加请求指纹与“完成前状态”，用于：
--    * 同一 idempotency_key 命中时比较操作类型与规范化内容，防止手工建单/通知提取/草案新建互相冒充；
--    * 取消完成时恢复用户完成前的真实状态（PENDING_CONFIRM 不能借完成/取消完成变成 ACTIVE）。
-- 2. 新增 t_ddl_decision_receipt：把 API-DDL-005 决策回执持久化到 MySQL，
--    Redis 回执降级为性能缓存，避免“数据库动作已提交但回执丢失”。
-- 3. 强化 chk_ddl_time_invariants，并清理历史 ACTIVE/OVERDUE 任务上不适用的确认原因。

ALTER TABLE `t_ddl_task`
  ADD COLUMN `request_operation` VARCHAR(32) DEFAULT NULL
    COMMENT '请求级幂等操作类型: MANUAL_CREATE, EXTRACT, DRAFT_CREATE_NEW' AFTER `source_msg_id`,
  ADD COLUMN `request_fingerprint` CHAR(64) DEFAULT NULL
    COMMENT '请求级幂等指纹: sha256(操作类型+规范化请求内容)，命中唯一键时用于判定是否同一请求' AFTER `request_operation`,
  ADD COLUMN `status_before_completion` VARCHAR(32) DEFAULT NULL
    COMMENT '标记完成前的状态快照，取消完成时据此恢复，避免把待确认任务恢复为 ACTIVE' AFTER `status`;

-- 历史 ACTIVE/OVERDUE 任务不应保留“待确认”原因（旧版本曾在取消完成后遗留）。
UPDATE `t_ddl_task`
   SET `time_ambiguity_reason` = NULL
 WHERE `status` IN ('ACTIVE', 'OVERDUE')
   AND `time_ambiguity_reason` IS NOT NULL;

CREATE TABLE IF NOT EXISTS `t_ddl_decision_receipt` (
  `receipt_id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '决策回执唯一自增主键',
  `user_id`            BIGINT       NOT NULL COMMENT '所属用户ID',
  `client_request_id`  VARCHAR(128) NOT NULL COMMENT 'API-DDL-005 的 client_request_id（请求级幂等键）',
  `request_fingerprint` VARCHAR(160) NOT NULL COMMENT '决策指纹: sha256(decision:target_task_id)',
  `decision`           VARCHAR(32)  NOT NULL COMMENT '决策类型: CONFIRM_UPDATE, CREATE_NEW, DISCARD',
  `target_task_id`     BIGINT       NOT NULL COMMENT '草案目标任务ID',
  `result_task_id`     BIGINT       DEFAULT NULL COMMENT '决策结果任务ID（DISCARD 为原任务）',
  `expires_at`         DATETIME(3)  NOT NULL COMMENT '回执过期时间（24h）',
  `created_at`         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '回执创建时间',
  PRIMARY KEY (`receipt_id`),
  UNIQUE KEY `uk_ddl_decision_receipt` (`user_id`, `client_request_id`),
  KEY `idx_ddl_decision_expires` (`expires_at`),
  CONSTRAINT `chk_ddl_decision_enums`
    CHECK (`decision` IN ('CONFIRM_UPDATE', 'CREATE_NEW', 'DISCARD'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='API-DDL-005 变更草案决策的持久化幂等回执';

-- 强化时间字段不变量：把此前仅由 Service 层保证的规则落实到数据库。
ALTER TABLE `t_ddl_task` DROP CHECK `chk_ddl_time_invariants`;
ALTER TABLE `t_ddl_task`
  ADD CONSTRAINT `chk_ddl_time_invariants` CHECK (
    (`status` <> 'PENDING_TIME'
      OR (`due_at` IS NULL AND `due_date` IS NULL AND `due_time` IS NULL))
    AND (`status` NOT IN ('ACTIVE', 'OVERDUE')
      OR (`due_at` IS NOT NULL AND `due_date` IS NOT NULL))
    AND (`status` <> 'PENDING_CONFIRM' OR `due_at` IS NOT NULL)
    AND (`time_precision` <> 'VAGUE_OR_MISSING'
      OR (`due_date` IS NULL AND `due_time` IS NULL AND `due_at` IS NULL
          AND `time_inferred` = 0))
    AND (`time_precision` <> 'DATE_ONLY'
      OR (`due_time` IS NULL AND `time_inferred` = 1 AND `due_at` IS NOT NULL))
    AND (`time_precision` <> 'EXACT_DATETIME'
      OR (`due_date` IS NOT NULL AND `due_time` IS NOT NULL AND `due_at` IS NOT NULL
          AND `time_inferred` = 0))
    AND (`time_precision` <> 'INVALID_OR_PAST'
      OR ((`due_date` IS NULL AND `due_time` IS NULL AND `due_at` IS NULL)
          OR (`due_date` IS NOT NULL AND `due_at` IS NOT NULL)))
    AND (`status` NOT IN ('ACTIVE', 'OVERDUE', 'COMPLETED', 'CANCELED')
      OR `follow_up_at` IS NULL)
    AND (`status` NOT IN ('PENDING_TIME', 'PENDING_CONFIRM')
      OR `follow_up_at` IS NOT NULL)
    AND (`status` <> 'COMPLETED' OR `completed_at` IS NOT NULL)
    AND (`status` NOT IN ('PENDING_TIME', 'PENDING_CONFIRM', 'ACTIVE', 'OVERDUE')
      OR `completed_at` IS NULL)
  );
