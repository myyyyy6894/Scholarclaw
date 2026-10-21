package com.scholarclaw.modules.course.event;

/**
 * 课程 Embedding 模型切换事件（{@code 04} `API-COURSE-003`）。
 *
 * <p>由 `course` 模块在本地事务内发布；`knowledge` 模块监听后负责把该课程下文档置为
 * `PARSING`、递增 `parse_version`、清空旧父块并投递 `REINDEX`。`course` 不直接写
 * {@code t_knowledge_doc}，保持 Single-Writer。
 */
public record CourseEmbeddingChangedEvent(
        Long userId,
        Long courseId,
        int previousIndexVersion,
        int currentIndexVersion,
        String embeddingProvider,
        String embeddingModel,
        int embeddingDim) {
}
