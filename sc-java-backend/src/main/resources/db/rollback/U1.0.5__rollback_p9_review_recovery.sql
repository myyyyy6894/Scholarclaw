-- U1.0.5：回滚 P9 复核新增的补偿 outbox 与课程删除回执表。
--
-- 仅在明确需要回退 1.0.5 时人工执行。执行前必须先确认两表已无待处理记录：
--   SELECT status, COUNT(*) FROM t_vector_cleanup_outbox GROUP BY status;
-- 回滚会丢失尚未确认的向量清理意图，可能导致 ChromaDB 残留。
DROP TABLE IF EXISTS `t_vector_cleanup_outbox`;
DROP TABLE IF EXISTS `t_course_deletion_receipt`;
