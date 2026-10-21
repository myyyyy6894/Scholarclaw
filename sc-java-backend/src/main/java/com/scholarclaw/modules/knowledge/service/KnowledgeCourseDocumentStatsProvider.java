package com.scholarclaw.modules.knowledge.service;

import com.scholarclaw.modules.course.service.CourseDocumentStatsProvider;
import com.scholarclaw.modules.knowledge.mapper.CourseDocStatsRow;
import com.scholarclaw.modules.knowledge.mapper.KnowledgeDocMapper;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 课程文档统计端口的 `knowledge` 实现（TASK-402）。
 *
 * <p>只统计未进入删除态（`DELETING`/`DELETED`）的文档；`parsing` 合并 `UPLOADED + PARSING`。
 * `course` 模块通过接口读取，不直接访问 {@code t_knowledge_doc}。
 */
@Service
public class KnowledgeCourseDocumentStatsProvider implements CourseDocumentStatsProvider {

    private final KnowledgeDocMapper docMapper;

    public KnowledgeCourseDocumentStatsProvider(KnowledgeDocMapper docMapper) {
        this.docMapper = docMapper;
    }

    @Override
    public Map<Long, CourseDocumentStats> statsByCourse(Long userId, Collection<Long> courseIds) {
        if (courseIds == null || courseIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, CourseDocumentStats> result = new HashMap<>();
        for (CourseDocStatsRow row : docMapper.selectCourseStats(userId, courseIds)) {
            result.put(row.getCourseId(), new CourseDocumentStats(
                    intValue(row.getReadyCount()),
                    intValue(row.getParsingCount()),
                    intValue(row.getFailedCount()),
                    intValue(row.getTotalCount())));
        }
        return result;
    }

    private static int intValue(Long value) {
        return value == null ? 0 : (int) Math.min(value, Integer.MAX_VALUE);
    }
}
