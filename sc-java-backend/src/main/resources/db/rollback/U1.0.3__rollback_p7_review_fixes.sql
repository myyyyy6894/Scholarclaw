-- U1.0.3：回滚 P7 第三轮审阅修复的数据库变更。
-- 回滚前必须确认没有依赖 t_ddl_decision_receipt 的调用，否则会丢失决策幂等回执。

DROP TABLE IF EXISTS `t_ddl_decision_receipt`;

ALTER TABLE `t_ddl_task` DROP CHECK `chk_ddl_time_invariants`;
ALTER TABLE `t_ddl_task`
  ADD CONSTRAINT `chk_ddl_time_invariants` CHECK (
    (`status` != 'PENDING_TIME'
      OR (`due_at` IS NULL AND `due_date` IS NULL AND `due_time` IS NULL))
    AND (`status` NOT IN ('ACTIVE', 'OVERDUE')
      OR (`due_at` IS NOT NULL AND `due_date` IS NOT NULL))
    AND (`time_precision` != 'DATE_ONLY'
      OR (`due_time` IS NULL AND `time_inferred` = 1))
  );

ALTER TABLE `t_ddl_task`
  DROP COLUMN `status_before_completion`,
  DROP COLUMN `request_fingerprint`,
  DROP COLUMN `request_operation`;
