-- U1.0.2：回滚 V1.0.2 增加的时间确认原因字段。
ALTER TABLE `t_ddl_task` DROP COLUMN `time_ambiguity_reason`;
