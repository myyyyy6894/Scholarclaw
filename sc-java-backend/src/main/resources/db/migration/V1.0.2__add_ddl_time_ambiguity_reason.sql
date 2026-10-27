-- V1.0.2：为 DDL 待确认任务增加可持久化的时间确认原因（TASK-705 审阅修复）。
-- 背景：Python 提取阶段已生成 ambiguity_reason（跨年顺延/周末“下周一”/凌晨跨日/低置信度/过去时间），
-- 但 Java 仅保存 has_ambiguity，导致 Web 卡片无法展示“为什么需要确认”与“按跨年顺延到哪一天”。
ALTER TABLE `t_ddl_task`
  ADD COLUMN `time_ambiguity_reason` VARCHAR(512) DEFAULT NULL
  COMMENT '时间确认原因: 跨年顺延/周末下周一/凌晨跨日/低置信度/过去时间等' AFTER `time_inferred`;
