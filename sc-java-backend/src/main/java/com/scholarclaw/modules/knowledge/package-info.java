/**
 * 模块 4：知识库文档与父块（第 2 层）。
 *
 * <p><b>职责：</b>{@code t_knowledge_doc} 与 {@code t_knowledge_parent_chunk} 的唯一读写拥有者；
 * 上传四重校验与共享卷落盘/删除；维护 {@code DocumentStatus} 六态与 {@code parse_version} 乐观锁；
 * 发布 {@code rag.ingest.queue} 消息并处理 {@code rag.ingest.dlq} 死信；实现内部接口
 * {@code /parse-context}、{@code /parent-chunks/batch-get}（四重权威校验）与 {@code /ingest-result}；
 * 监听 {@code CourseEmbeddingChangedEvent} / {@code CourseDeletingEvent} / {@code UserDeactivatingEvent}。
 *
 * <p><b>允许依赖：</b>{@code course}、{@code userconfig}、{@code notification}、{@code common}。
 *
 * <p><b>禁止：</b>不执行 PDF 解析与分块算法（属 Python）；不直连 ChromaDB（通过
 * {@code PythonAiEngineClient} 调 Python 清理向量）；<b>不得把 ChromaDB 当作文档状态的权威源</b>——
 * 有效文档集永远以 MySQL 的 {@code status = 'READY'} 且 {@code course_index_version = index_version}
 * 为准。
 *
 * <p><b>唯一写入权：</b>MySQL {@code t_knowledge_doc}、{@code t_knowledge_parent_chunk}；
 * 共享卷 {@code /data/scholarclaw/storage}（rw）；RabbitMQ {@code rag.ingest.queue / dlq}。
 *
 * <p>落地任务：TASK-402（元数据与状态机）、TASK-403（安全上传）、TASK-501~506（异步解析与索引）、
 * TASK-601/602（检索与父块回查）、TASK-901/902（级联清理与 GC）。
 */
package com.scholarclaw.modules.knowledge;
