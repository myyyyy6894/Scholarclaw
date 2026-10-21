package com.scholarclaw.modules.knowledge.mapper;

/** 课程文档统计聚合行（`CourseDocumentStatsProvider` 使用）。 */
public class CourseDocStatsRow {

    private Long courseId;
    private Long readyCount;
    private Long parsingCount;
    private Long failedCount;
    private Long totalCount;

    public Long getCourseId() {
        return courseId;
    }

    public void setCourseId(Long courseId) {
        this.courseId = courseId;
    }

    public Long getReadyCount() {
        return readyCount;
    }

    public void setReadyCount(Long readyCount) {
        this.readyCount = readyCount;
    }

    public Long getParsingCount() {
        return parsingCount;
    }

    public void setParsingCount(Long parsingCount) {
        this.parsingCount = parsingCount;
    }

    public Long getFailedCount() {
        return failedCount;
    }

    public void setFailedCount(Long failedCount) {
        this.failedCount = failedCount;
    }

    public Long getTotalCount() {
        return totalCount;
    }

    public void setTotalCount(Long totalCount) {
        this.totalCount = totalCount;
    }
}
