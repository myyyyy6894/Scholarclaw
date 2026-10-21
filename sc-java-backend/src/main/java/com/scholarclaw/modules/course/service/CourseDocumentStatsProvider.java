package com.scholarclaw.modules.course.service;

import java.util.Collection;
import java.util.Map;

/**
 * 课程文档统计只读端口（解决 Strict DAG：`course` 不得依赖 `knowledge`）。
 *
 * <p>`knowledge` 模块实现本接口后，`API-COURSE-001` 才能返回真实文档计数；实现缺失时
 * `course` 明确返回 0，不伪造统计数据。计数只按 {@code user_id + course_id} 过滤。
 */
public interface CourseDocumentStatsProvider {

    Map<Long, CourseDocumentStats> statsByCourse(Long userId, Collection<Long> courseIds);

    /** 单课程统计；{@code total} 用于判断切换模型后是否需要进入 `REBUILDING`。 */
    record CourseDocumentStats(int ready, int parsing, int failed, int total) {

        public static final CourseDocumentStats ZERO = new CourseDocumentStats(0, 0, 0, 0);
    }
}
