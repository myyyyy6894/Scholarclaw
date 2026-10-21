package com.scholarclaw.modules.knowledge.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.scholarclaw.common.enums.DocumentStatus;
import com.scholarclaw.common.enums.IndexStatus;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.course.entity.CourseNamespaceEntity;
import com.scholarclaw.modules.course.service.CourseService;
import com.scholarclaw.modules.knowledge.dto.KnowledgeDocVO;
import com.scholarclaw.modules.knowledge.entity.KnowledgeDocEntity;
import com.scholarclaw.modules.knowledge.mapper.KnowledgeDocMapper;
import com.scholarclaw.modules.knowledge.service.FileUploadSecurityValidator.ValidatedUpload;
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import com.scholarclaw.modules.userconfig.service.ModelConfigRules;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 课件上传流程（{@code 06} TASK-403、{@code 04} §4.4.2 `API-DOC-002`）。
 *
 * <p>顺序：Embedding 准入 → 课程归属/索引状态 → 文件四重校验 → SHA-256 查重 → 元数据落库
 * → 临时文件移动到 raw → 回填路径。失败路径会删除临时文件，并补偿删除未提交的 `UPLOADED` 元数据。
 *
 * <p><b>本任务不发 MQ</b>：成功后文档保持 `UPLOADED`，由 TASK-501 的异步任务生产器发布
 * `INITIAL_INGEST` 并推进为 `PARSING`。
 */
@Service
public class KnowledgeUploadService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeUploadService.class);

    private final KnowledgeDocMapper docMapper;
    private final KnowledgeDocService docService;
    private final CourseService courseService;
    private final UserConfigService userConfigService;
    private final ModelConfigRules modelConfigRules;
    private final FileUploadSecurityValidator uploadValidator;
    private final StorageService storageService;

    public KnowledgeUploadService(KnowledgeDocMapper docMapper,
                                  KnowledgeDocService docService,
                                  CourseService courseService,
                                  UserConfigService userConfigService,
                                  ModelConfigRules modelConfigRules,
                                  FileUploadSecurityValidator uploadValidator,
                                  StorageService storageService) {
        this.docMapper = docMapper;
        this.docService = docService;
        this.courseService = courseService;
        this.userConfigService = userConfigService;
        this.modelConfigRules = modelConfigRules;
        this.uploadValidator = uploadValidator;
        this.storageService = storageService;
    }

    public KnowledgeDocVO upload(Long userId, Long courseId, MultipartFile file) {
        UserConfigEntity config = userConfigService.findById(userId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.AUTH_UNAUTHORIZED, "登录状态已失效，请重新登录。"));
        if (UserConfigService.isDeactivating(config)) {
            throw new BusinessException(ErrorCode.ACCOUNT_DEACTIVATED);
        }
        if (!modelConfigRules.isEmbeddingConfigValid(config)) {
            throw new BusinessException(ErrorCode.E_SYS_01,
                    "⚠️ 尚未配置完整的向量模型（Embedding），请先前往【个人设置 -> 模型配置】完成配置。");
        }
        CourseNamespaceEntity course = courseService.getActiveCourse(userId, courseId);
        if (IndexStatus.REBUILDING.name().equals(course.getIndexStatus())) {
            throw new BusinessException(ErrorCode.E_SYS_03,
                    "当前课程索引正在重建中，请等待完成后再上传。");
        }

        ValidatedUpload staged = uploadValidator.validateAndStage(file);
        try {
            if (existsActiveSha(userId, courseId, staged.sha256())) {
                throw new BusinessException(ErrorCode.DOC_DUPLICATE_SHA256);
            }
            KnowledgeDocEntity created = docService.createDocumentMetadata(
                    userId, courseId, staged.safeName(), staged.extension(),
                    staged.size(), staged.sha256(), "pending");
            String relativePath;
            try {
                relativePath = storageService.moveToRaw(staged.tempFile(), userId, courseId,
                        created.getDocId(), staged.extension());
                docService.updateStorageRelPath(userId, created.getDocId(), relativePath);
            } catch (RuntimeException e) {
                // 文件移动/回填失败：删除未提交元数据与可能已落下的目标文件，避免半成品
                docService.deleteUncommittedMetadata(userId, created.getDocId());
                storageService.deleteRawQuietly(userId, courseId, created.getDocId(), staged.extension());
                log.warn("课件落盘失败，已补偿删除元数据 docId={} error={}",
                        created.getDocId(), e.getClass().getSimpleName());
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "课件保存失败，请稍后重试。");
            }
            created.setStorageRelPath(relativePath);
            return docService.toVO(created);
        } catch (RuntimeException e) {
            storageService.deleteQuietly(staged.tempFile());
            throw e;
        }
    }

    private boolean existsActiveSha(Long userId, Long courseId, String sha256) {
        return docMapper.selectCount(Wrappers.<KnowledgeDocEntity>lambdaQuery()
                .eq(KnowledgeDocEntity::getUserId, userId)
                .eq(KnowledgeDocEntity::getCourseId, courseId)
                .eq(KnowledgeDocEntity::getFileSha256, sha256)
                .notIn(KnowledgeDocEntity::getStatus,
                        DocumentStatus.DELETING.name(), DocumentStatus.DELETED.name())) > 0;
    }
}
