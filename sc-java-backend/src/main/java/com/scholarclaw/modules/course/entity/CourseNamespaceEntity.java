package com.scholarclaw.modules.course.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * {@code t_course_namespace} 实体（{@code 03} §4.2，Single-Writer = `course` 模块）。
 *
 * <p>生成列 {@code active_course_name_key} 与 {@code default_course_uk} 由 MySQL 计算，
 * 插入时保持 {@code null}（MyBatis-Plus 默认不写入 null 字段）；查询时只用于观察，
 * 业务代码不得写入。
 */
@TableName("t_course_namespace")
public class CourseNamespaceEntity {

    @TableId(value = "course_id", type = IdType.AUTO)
    private Long courseId;

    private Long userId;
    private String courseName;
    private String description;
    private Integer isDefault;
    private String embeddingProvider;
    private String embeddingModel;
    private Integer embeddingDim;
    private Integer indexVersion;
    private String indexStatus;
    private Integer isDeleting;

    /** MySQL 生成列：只读，插入/更新时绝不写入。 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private String activeCourseNameKey;

    /** MySQL 生成列：只读，插入/更新时绝不写入。 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Long defaultCourseUk;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getCourseId() {
        return courseId;
    }

    public void setCourseId(Long courseId) {
        this.courseId = courseId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getCourseName() {
        return courseName;
    }

    public void setCourseName(String courseName) {
        this.courseName = courseName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Integer getIsDefault() {
        return isDefault;
    }

    public void setIsDefault(Integer isDefault) {
        this.isDefault = isDefault;
    }

    public String getEmbeddingProvider() {
        return embeddingProvider;
    }

    public void setEmbeddingProvider(String embeddingProvider) {
        this.embeddingProvider = embeddingProvider;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public void setEmbeddingModel(String embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public Integer getEmbeddingDim() {
        return embeddingDim;
    }

    public void setEmbeddingDim(Integer embeddingDim) {
        this.embeddingDim = embeddingDim;
    }

    public Integer getIndexVersion() {
        return indexVersion;
    }

    public void setIndexVersion(Integer indexVersion) {
        this.indexVersion = indexVersion;
    }

    public String getIndexStatus() {
        return indexStatus;
    }

    public void setIndexStatus(String indexStatus) {
        this.indexStatus = indexStatus;
    }

    public Integer getIsDeleting() {
        return isDeleting;
    }

    public void setIsDeleting(Integer isDeleting) {
        this.isDeleting = isDeleting;
    }

    public String getActiveCourseNameKey() {
        return activeCourseNameKey;
    }

    public void setActiveCourseNameKey(String activeCourseNameKey) {
        this.activeCourseNameKey = activeCourseNameKey;
    }

    public Long getDefaultCourseUk() {
        return defaultCourseUk;
    }

    public void setDefaultCourseUk(Long defaultCourseUk) {
        this.defaultCourseUk = defaultCourseUk;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
