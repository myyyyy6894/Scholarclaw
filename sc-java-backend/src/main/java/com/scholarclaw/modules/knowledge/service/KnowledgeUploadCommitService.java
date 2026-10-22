package com.scholarclaw.modules.knowledge.service;

import com.scholarclaw.common.enums.IndexStatus;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.course.entity.CourseNamespaceEntity;
import com.scholarclaw.modules.course.service.CourseService;
import com.scholarclaw.modules.knowledge.dto.KnowledgeDocVO;
import com.scholarclaw.modules.knowledge.entity.KnowledgeDocEntity;
import com.scholarclaw.modules.knowledge.service.FileUploadSecurityValidator.ValidatedUpload;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 上传的数据库事务边界（{@code 04} §4.4.2）。
 *
 * <p>事务内依次完成：课程行锁 → 索引状态校验 → 查重 → 元数据插入 → 文件移动 → 路径回填。
 * 任一步失败整体回滚，不会留下 `UPLOADED + pending` 半成品；文件系统的补偿删除失败会以
 * ERROR 日志记录具体 `doc_id` 与相对路径，供 {@link KnowledgeUploadRecoveryService} 兜底。
 */
@Service
public class KnowledgeUploadCommitService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeUploadCommitService.class);

    private final KnowledgeDocService docService;
    private final CourseService courseService;
    private final StorageService storageService;

    public KnowledgeUploadCommitService(KnowledgeDocService docService,
                                        CourseService courseService,
                                        StorageService storageService) {
        this.docService = docService;
        this.courseService = courseService;
        this.storageService = storageService;
    }

    @Transactional(rollbackFor = Exception.class)
    public KnowledgeDocVO commit(Long userId, Long courseId, ValidatedUpload staged) {
        CourseNamespaceEntity course = courseService.getActiveCourseForUpdate(userId, courseId);
        if (IndexStatus.REBUILDING.name().equals(course.getIndexStatus())) {
            throw new BusinessException(ErrorCode.E_SYS_03,
                    "当前课程索引正在重建中，请等待完成后再上传。");
        }
        KnowledgeDocEntity existing = docService.findActiveShaDoc(userId, courseId, staged.sha256());
        if (existing != null) {
            throw duplicate(existing);
        }

        KnowledgeDocEntity created;
        try {
            created = docService.createDocumentMetadata(
                    course, staged.safeName(), staged.extension(), staged.size(),
                    staged.sha256(), "pending");
        } catch (DuplicateKeyException e) {
            // 唯一索引并发兜底：回查胜出的文档并返回结构化详情
            KnowledgeDocEntity winner = docService.findActiveShaDoc(userId, courseId, staged.sha256());
            throw winner == null ? new BusinessException(ErrorCode.DOC_DUPLICATE_SHA256) : duplicate(winner);
        }

        String relativePath;
        try {
            relativePath = storageService.moveToRaw(staged.tempFile(), userId, courseId,
                    created.getDocId(), staged.extension());
            docService.updateStorageRelPath(userId, created.getDocId(), relativePath);
        } catch (RuntimeException e) {
            String expectedPath = storageService.rawRelativePath(
                    userId, courseId, created.getDocId(), staged.extension());
            if (!storageService.deleteRawQuietly(userId, courseId, created.getDocId(), staged.extension())) {
                log.error("上传补偿删除失败，需恢复扫描兜底 docId={} path={}",
                        created.getDocId(), expectedPath);
            }
            log.warn("课件落盘失败，事务回滚 docId={} error={}",
                    created.getDocId(), e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "课件保存失败，请稍后重试。");
        }
        created.setStorageRelPath(relativePath);
        return docService.toVO(created);
    }

    private static BusinessException duplicate(KnowledgeDocEntity existing) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("existing_doc_id", String.valueOf(existing.getDocId()));
        detail.put("existing_doc_name", existing.getDocName());
        return new BusinessException(ErrorCode.DOC_DUPLICATE_SHA256,
                "该课件已存在于当前课程知识库中，无需重复上传。", detail);
    }
}
