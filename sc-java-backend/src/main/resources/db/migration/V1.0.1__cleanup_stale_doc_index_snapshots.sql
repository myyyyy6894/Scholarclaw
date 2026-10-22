-- V1.0.1：一次性修复历史/异常中断数据残留的索引快照（幂等）。
-- 只有 READY 文档允许保留 indexed_* / page_count / chunk_count / ready_at；
-- 其他状态在对外 API 已按状态隐藏，这里同步清理数据库真实值，避免旧数据继续误导排查。
UPDATE t_knowledge_doc
   SET indexed_embedding_provider = NULL,
       indexed_embedding_model = NULL,
       indexed_embedding_dim = NULL,
       page_count = 0,
       chunk_count = 0,
       ready_at = NULL
 WHERE status <> 'READY'
   AND (indexed_embedding_provider IS NOT NULL
        OR indexed_embedding_model IS NOT NULL
        OR indexed_embedding_dim IS NOT NULL
        OR page_count <> 0
        OR chunk_count <> 0
        OR ready_at IS NOT NULL);
