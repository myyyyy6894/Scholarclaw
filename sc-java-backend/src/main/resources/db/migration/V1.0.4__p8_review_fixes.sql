-- V1.0.4：P8 复核修复（第二次跟进后 follow_up_at 置空）。
--
-- 契约统一：PENDING_TIME / PENDING_CONFIRM 任务在 follow_up_count < 2 时 follow_up_at 非空；
-- follow_up_count = 2 时 follow_up_at 必须为 NULL，不再产生第三次跟进。
-- 与 docs/03 §6.3 场景 SQL 的 "CASE WHEN follow_up_count + 1 < 2 THEN ... ELSE NULL END" 对齐。

-- 1. 先移除旧约束：旧 CHECK 要求待确认任务 follow_up_at 非空，
--    直接 UPDATE 为 NULL 会被旧约束拒绝，所以必须“先删约束 → 改数据 → 加新约束”。
ALTER TABLE `t_ddl_task` DROP CHECK `chk_ddl_time_invariants`;

-- 2. 清理与统一历史数据：
--    * 已满 2 次的待确认任务清空 follow_up_at（旧实现会写入未来时间）；
--    * 未满 2 次但 follow_up_at 为空的脏数据补一个保守时间，避免新约束无法建立。
UPDATE `t_ddl_task`
   SET `follow_up_at` = NULL
 WHERE `status` IN ('PENDING_TIME', 'PENDING_CONFIRM')
   AND `follow_up_count` >= 2
   AND `follow_up_at` IS NOT NULL;

UPDATE `t_ddl_task`
   SET `follow_up_at` = NOW(3)
 WHERE `status` IN ('PENDING_TIME', 'PENDING_CONFIRM')
   AND `follow_up_count` < 2
   AND `follow_up_at` IS NULL;

-- 3. 重建时间字段不变量约束。
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
      OR (`follow_up_count` < 2 AND `follow_up_at` IS NOT NULL)
      OR (`follow_up_count` >= 2 AND `follow_up_at` IS NULL))
    AND (`status` <> 'COMPLETED' OR `completed_at` IS NOT NULL)
    AND (`status` NOT IN ('PENDING_TIME', 'PENDING_CONFIRM', 'ACTIVE', 'OVERDUE')
      OR `completed_at` IS NULL)
  );
