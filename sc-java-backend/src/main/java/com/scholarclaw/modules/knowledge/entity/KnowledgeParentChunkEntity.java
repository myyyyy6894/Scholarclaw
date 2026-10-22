package com.scholarclaw.modules.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** {@code t_knowledge_parent_chunk} 实体（{@code 03} §4.4，Single-Writer = `knowledge`）。 */
@TableName("t_knowledge_parent_chunk")
public class KnowledgeParentChunkEntity {

    @TableId(value = "parent_chunk_id", type = IdType.INPUT)
    private String parentChunkId;
    private Long docId;
    private Long userId;
    private Long courseId;
    private Integer parseVersion;
    private Integer chunkSeq;
    private Integer pageStart;
    private Integer pageEnd;
    private String sectionTitle;
    private String content;
    private Integer charLength;
    private LocalDateTime createdAt;

    public String getParentChunkId() {
        return parentChunkId;
    }

    public void setParentChunkId(String parentChunkId) {
        this.parentChunkId = parentChunkId;
    }

    public Long getDocId() {
        return docId;
    }

    public void setDocId(Long docId) {
        this.docId = docId;
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

    public Integer getParseVersion() {
        return parseVersion;
    }

    public void setParseVersion(Integer parseVersion) {
        this.parseVersion = parseVersion;
    }

    public Integer getChunkSeq() {
        return chunkSeq;
    }

    public void setChunkSeq(Integer chunkSeq) {
        this.chunkSeq = chunkSeq;
    }

    public Integer getPageStart() {
        return pageStart;
    }

    public void setPageStart(Integer pageStart) {
        this.pageStart = pageStart;
    }

    public Integer getPageEnd() {
        return pageEnd;
    }

    public void setPageEnd(Integer pageEnd) {
        this.pageEnd = pageEnd;
    }

    public String getSectionTitle() {
        return sectionTitle;
    }

    public void setSectionTitle(String sectionTitle) {
        this.sectionTitle = sectionTitle;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Integer getCharLength() {
        return charLength;
    }

    public void setCharLength(Integer charLength) {
        this.charLength = charLength;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
