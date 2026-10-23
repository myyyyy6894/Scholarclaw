package com.scholarclaw.modules.knowledge.mapper;

/**
 * 父块权威回查行（`API-INT-JAVA-002`，TASK-602）。
 *
 * <p>只承载三表关联校验通过后的 MySQL 权威字段；调用方不得用 ChromaDB 元数据补齐。
 */
public class AuthoritativeParentChunkRow {

    private String parentChunkId;
    private Long docId;
    private String docName;
    private Integer pageStart;
    private Integer pageEnd;
    private String sectionTitle;
    private String content;

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

    public String getDocName() {
        return docName;
    }

    public void setDocName(String docName) {
        this.docName = docName;
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
}
