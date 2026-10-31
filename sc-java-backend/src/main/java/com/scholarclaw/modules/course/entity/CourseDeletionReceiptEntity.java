package com.scholarclaw.modules.course.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * {@code t_course_deletion_receipt} 实体（{@code 03} §4.11，Single-Writer = `course` 模块）。
 *
 * <p>课程行物理删除后仍保留的最小墓碑：只记录归属、课程 ID 与结果状态，
 * 不含课程名或任何内容数据；用于删除接口幂等与跨用户不可见。
 */
@TableName("t_course_deletion_receipt")
public class CourseDeletionReceiptEntity {

    @TableId(value = "receipt_id", type = IdType.AUTO)
    private Long receiptId;

    private Long userId;
    private Long courseId;
    private String resultStatus;
    private LocalDateTime createdAt;

    public Long getReceiptId() {
        return receiptId;
    }

    public void setReceiptId(Long receiptId) {
        this.receiptId = receiptId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getCourseId() {
        return courseId;
    }

    public void setCourseId(Long courseId) {
        this.courseId = courseId;
    }

    public String getResultStatus() {
        return resultStatus;
    }

    public void setResultStatus(String resultStatus) {
        this.resultStatus = resultStatus;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
