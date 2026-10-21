package com.scholarclaw.modules.knowledge.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * {@code t_knowledge_doc} 实体（{@code 03} §4.3，Single-Writer = `knowledge` 模块）。
 *
 * <p>生成列 {@code active_file_sha256} 只读，插入/更新时绝不写入。
 * 所有状态变更必须带 {@code parse_version} 条件，见 {@link com.scholarclaw.modules.knowledge.service.KnowledgeDocService}。
 */
@TableName("t_knowledge_doc")
public class KnowledgeDocEntity {

    @TableId(value = "doc_id", type = IdType.AUTO)
    private Long docId;
    private Long userId;
    private Long courseId;
    private String docName;
    private String fileExt;
    private Long fileSizeBytes;
    private String fileSha256;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private String activeFileSha256;

    private String storageRelPath;
    private String status;
    private Integer parseVersion;
    private Integer courseIndexVersion;
    private String indexedEmbeddingProvider;
    private String indexedEmbeddingModel;
    private Integer indexedEmbeddingDim;
    private Integer pageCount;
    private Integer chunkCount;
    private Integer ocrUsed;
    private String lastIngestJobId;
    private Integer retryCount;
    private String errorCode;
    private String errorMsg;
    private LocalDateTime enqueuedAt;
    private LocalDateTime readyAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

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

    public String getDocName() {
        return docName;
    }

    public void setDocName(String docName) {
        this.docName = docName;
    }

    public String getFileExt() {
        return fileExt;
    }

    public void setFileExt(String fileExt) {
        this.fileExt = fileExt;
    }

    public Long getFileSizeBytes() {
        return fileSizeBytes;
    }

    public void setFileSizeBytes(Long fileSizeBytes) {
        this.fileSizeBytes = fileSizeBytes;
    }

    public String getFileSha256() {
        return fileSha256;
    }

    public void setFileSha256(String fileSha256) {
        this.fileSha256 = fileSha256;
    }

    public String getActiveFileSha256() {
        return activeFileSha256;
    }

    public void setActiveFileSha256(String activeFileSha256) {
        this.activeFileSha256 = activeFileSha256;
    }

    public String getStorageRelPath() {
        return storageRelPath;
    }

    public void setStorageRelPath(String storageRelPath) {
        this.storageRelPath = storageRelPath;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getParseVersion() {
        return parseVersion;
    }

    public void setParseVersion(Integer parseVersion) {
        this.parseVersion = parseVersion;
    }

    public Integer getCourseIndexVersion() {
        return courseIndexVersion;
    }

    public void setCourseIndexVersion(Integer courseIndexVersion) {
        this.courseIndexVersion = courseIndexVersion;
    }

    public String getIndexedEmbeddingProvider() {
        return indexedEmbeddingProvider;
    }

    public void setIndexedEmbeddingProvider(String indexedEmbeddingProvider) {
        this.indexedEmbeddingProvider = indexedEmbeddingProvider;
    }

    public String getIndexedEmbeddingModel() {
        return indexedEmbeddingModel;
    }

    public void setIndexedEmbeddingModel(String indexedEmbeddingModel) {
        this.indexedEmbeddingModel = indexedEmbeddingModel;
    }

    public Integer getIndexedEmbeddingDim() {
        return indexedEmbeddingDim;
    }

    public void setIndexedEmbeddingDim(Integer indexedEmbeddingDim) {
        this.indexedEmbeddingDim = indexedEmbeddingDim;
    }

    public Integer getPageCount() {
        return pageCount;
    }

    public void setPageCount(Integer pageCount) {
        this.pageCount = pageCount;
    }

    public Integer getChunkCount() {
        return chunkCount;
    }

    public void setChunkCount(Integer chunkCount) {
        this.chunkCount = chunkCount;
    }

    public Integer getOcrUsed() {
        return ocrUsed;
    }

    public void setOcrUsed(Integer ocrUsed) {
        this.ocrUsed = ocrUsed;
    }

    public String getLastIngestJobId() {
        return lastIngestJobId;
    }

    public void setLastIngestJobId(String lastIngestJobId) {
        this.lastIngestJobId = lastIngestJobId;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }

    public LocalDateTime getEnqueuedAt() {
        return enqueuedAt;
    }

    public void setEnqueuedAt(LocalDateTime enqueuedAt) {
        this.enqueuedAt = enqueuedAt;
    }

    public LocalDateTime getReadyAt() {
        return readyAt;
    }

    public void setReadyAt(LocalDateTime readyAt) {
        this.readyAt = readyAt;
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
