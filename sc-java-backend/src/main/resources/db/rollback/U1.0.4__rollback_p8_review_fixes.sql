-- U1.0.4：回滚 P8 复核修复（follow_up_at 置空语义）。
-- 回滚会恢复 V1.0.3 的“待确认任务 follow_up_at 非空”约束；因此先把已满 2 次且为空的行
-- 补成当前时间，避免重建旧约束失败。该时间仅用于满足旧约束，不代表业务上还会继续提醒。
-- 必须先删除新约束，否则 UPDATE 会被新约束（count=2 时必须为 NULL）拒绝。

ALTER TABLE `t_ddl_task` DROP CHECK `chk_ddl_time_invariants`;

UPDATE `t_ddl_task`
   SET `follow_up_at` = NOW(3)
 WHERE `status` IN ('PENDING_TIME', 'PENDING_CONFIRM')
   AND `follow_up_count` >= 2
   AND `follow_up_at` IS NULL;

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
