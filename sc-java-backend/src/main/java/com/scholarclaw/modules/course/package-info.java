/**
 * 模块 3：课程空间与索引版本（第 3 层）。
 *
 * <p><b>职责：</b>{@code t_course_namespace} 表的唯一读写拥有者；新建课程时从
 * {@code UserConfigService} 复制默认 Embedding 三元组并锁定；切换课程 Embedding 模型时，
 * 探测新模型成功后 {@code index_version + 1} 且 {@code index_status = 'REBUILDING'}，
 * 并发布 {@code CourseEmbeddingChangedEvent}；删除课程时标记删除并发布 {@code CourseDeletingEvent}。
 *
 * <p><b>允许依赖：</b>{@code userconfig}、{@code common}。
 *
 * <p><b>禁止：</b>不得直接操作 {@code t_knowledge_doc}、{@code t_knowledge_parent_chunk} 或
 * {@code t_ddl_task}；不得在同一课程索引内混用不同 Embedding 模型的向量
 * （同一 {@code index_version} 下只能有唯一的 {@code embedding_model} + 维度）。
 *
 * <p><b>唯一写入权：</b>MySQL {@code t_course_namespace}。
 *
 * <p>落地任务：TASK-401（课程空间与 Embedding 绑定）、TASK-901（级联清理）。
 */
package com.scholarclaw.modules.course;
