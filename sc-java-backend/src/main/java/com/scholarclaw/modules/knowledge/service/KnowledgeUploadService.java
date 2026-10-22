package com.scholarclaw.modules.knowledge.service;

import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.course.service.CourseService;
import com.scholarclaw.modules.knowledge.dto.KnowledgeDocVO;
import com.scholarclaw.modules.knowledge.service.FileUploadSecurityValidator.ValidatedUpload;
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import com.scholarclaw.modules.userconfig.service.ModelConfigRules;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 课件上传流程（{@code 06} TASK-403、{@code 04} §4.4.2 `API-DOC-002`）。
 *
 * <p>顺序：Embedding 准入 → 文件四重校验（流式写入 `tmp/`）→ 事务内课程行锁、查重、
 * 元数据落库、移动到 raw、路径回填。文件校验不占用数据库事务；数据库与文件移动在
 * {@link KnowledgeUploadCommitService} 的单一事务内完成，失败整体回滚。
 *
 * <p><b>异步解析：</b>事务提交后由 {@link KnowledgeIngestService} 发布 `INITIAL_INGEST`；
 * 发布成功才推进为 `PARSING`，发布失败保留 `UPLOADED` 等待僵尸巡检补投。
 * 真正的解析消费者属 TASK-502。
 */
@Service
public class KnowledgeUploadService {

    private final KnowledgeUploadCommitService commitService;
    private final KnowledgeIngestService ingestService;
    private final KnowledgeDocService docService;
    private final CourseService courseService;
    private final UserConfigService userConfigService;
    private final ModelConfigRules modelConfigRules;
    private final FileUploadSecurityValidator uploadValidator;
    private final StorageService storageService;

    public KnowledgeUploadService(KnowledgeUploadCommitService commitService,
                                  KnowledgeIngestService ingestService,
                                  KnowledgeDocService docService,
                                  CourseService courseService,
                                  UserConfigService userConfigService,
                                  ModelConfigRules modelConfigRules,
                                  FileUploadSecurityValidator uploadValidator,
                                  StorageService storageService) {
        this.commitService = commitService;
        this.ingestService = ingestService;
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
        // 快速失败：事务内还会用行锁重新校验课程归属与索引状态
        var course = courseService.getActiveCourse(userId, courseId);
        if (com.scholarclaw.common.enums.IndexStatus.REBUILDING.name()
                .equals(course.getIndexStatus())) {
            throw new BusinessException(ErrorCode.E_SYS_03,
                    "当前课程索引正在重建中，请等待完成后再上传。");
        }

        ValidatedUpload staged = uploadValidator.validateAndStage(file);
        try {
            KnowledgeDocVO committed = commitService.commit(userId, courseId, staged);
            // 事务已提交：发布 INITIAL_INGEST；失败时保留 UPLOADED，由僵尸巡检补投
            ingestService.enqueueInitial(userId, Long.valueOf(committed.docId()));
            return docService.toVO(docService.requireOwnedDoc(userId, Long.valueOf(committed.docId())));
        } finally {
            storageService.deleteQuietly(staged.tempFile());
        }
    }
}
