package com.scholarclaw.modules.course.service;

import java.util.Map;

/**
 * 课程文档统计只读端口（解决 Strict DAG：`course` 不得依赖 `knowledge`）。
 *
 * <p>`knowledge` 模块实现本接口后，`API-COURSE-001` 才能返回真实文档计数；实现缺失或读取
 * 失败时由调用方明确失败（500），绝不静默返回全 0。
 *
 * <p>`ready` 只统计 {@code status = 'READY' AND course_index_version = 课程当前版本} 的文档，
 * 旧版本残留的 `READY` 文档不能计入可用数。
 */
public interface CourseDocumentStatsProvider {

    /**
     * @param courseIndexVersions 课程 ID → 课程当前 {@code index_version}；实现方按该版本过滤 `READY`
     */
    Map<Long, CourseDocumentStats> statsByCourse(Long userId, Map<Long, Integer> courseIndexVersions);

    /** 单课程统计；{@code total} 用于判断切换模型后是否需要进入 `REBUILDING`。 */
    record CourseDocumentStats(int ready, int parsing, int failed, int total) {

        public static final CourseDocumentStats ZERO = new CourseDocumentStats(0, 0, 0, 0);
    }
}
