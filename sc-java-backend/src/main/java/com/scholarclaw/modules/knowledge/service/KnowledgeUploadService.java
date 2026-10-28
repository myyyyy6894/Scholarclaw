package com.scholarclaw.modules.knowledge.service;

import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.course.service.CourseService;
import com.scholarclaw.modules.knowledge.dto.KnowledgeDocVO;
import com.scholarclaw.modules.knowledge.service.FileUploadSecurityValidator.ValidatedUpload;
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import com.scholarclaw.modules.userconfig.service.ModelConfigRules;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 课件上传流程（{@code 06} TASK-403、{@code 04} §4.4.2 `API-DOC-002`）。
 *
 * <p>顺序：Embedding 准入 → 文件四重校验（流式写入 `tmp/`）→ 事务内课程行锁、查重、
 * 元数据落库、移动到 raw、路径回填。文件校验不占用数据库事务；数据库与文件移动在
 * {@link KnowledgeUploadCommitService} 的单一事务内完成，失败整体回滚。
 *
 * <p><b>异步解析：</b>事务提交后先原子认领为 `PARSING`，再由 {@link KnowledgeIngestService}
 * 发布 `INITIAL_INGEST`；broker 确认后回填 `enqueued_at`。发布失败时保持
 * `PARSING + enqueued_at=NULL`，由僵尸巡检按同一任务补投。
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

    public KnowledgeDocVO upload(Long userId, Long courseId, MultipartFile file) {        UserConfigEntity config = userConfigService.findById(userId)
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
            // 事务已提交：先认领 PARSING 再发布；未获 broker 确认时由巡检补投
            ingestService.enqueueInitial(userId, Long.valueOf(committed.docId()));
            return docService.toVO(docService.requireOwnedDoc(userId, Long.valueOf(committed.docId())));
        } finally {
            storageService.deleteQuietly(staged.tempFile());
        }
    }

    /**
     * 非 HTTP multipart 来源（OneBot 文件事件）的入库入口（TASK-803）。
     *
     * <p>复用与 Web 上传完全相同的 Embedding 准入、四重安全校验、落盘与 MQ 发布链路；
     * 文件内容已在调用方按 50MB 上限下载到内存，这里转成只读 {@link MultipartFile}。
     */
    public KnowledgeDocVO uploadBytes(Long userId, Long courseId, String fileName,
                                      String contentType, byte[] content) {
        if (content == null || content.length == 0) {
            throw new BusinessException(ErrorCode.FILE_SIZE_EXCEEDED);
        }
        return upload(userId, courseId,
                new ByteArrayMultipartFile(fileName, contentType, content));
    }

    /**
     * OneBot 文件默认归入【未分类课程】（{@code 03} §4.2.1）并复用上传链路。
     *
     * <p>`course` 模块只允许 `knowledge` 依赖，`channel` 不直接注入 `CourseService`（Strict DAG）；
     * 因此由本方法完成默认课程懒加载，返回课程名供 IM 回执使用。
     */
    public DefaultCourseUpload uploadToDefaultCourse(Long userId, String fileName,
                                                     String contentType, byte[] content) {
        com.scholarclaw.modules.course.entity.CourseNamespaceEntity course =
                courseService.getOrCreateDefaultCourse(userId);
        KnowledgeDocVO doc = uploadBytes(userId, course.getCourseId(), fileName,
                contentType, content);
        return new DefaultCourseUpload(doc, course.getCourseName());
    }

    /** 默认课程入库结果；课程名用于 IM 回执，避免 channel 依赖 course 模块。 */
    public record DefaultCourseUpload(KnowledgeDocVO doc, String courseName) {
    }

    /** 只读内存文件；不写临时目录，临时文件仍由 {@link FileUploadSecurityValidator} 统一创建。 */
    private record ByteArrayMultipartFile(String originalFilename, String contentType, byte[] content)
            implements MultipartFile {

        @Override
        public String getName() {
            return "file";
        }

        @Override
        public String getOriginalFilename() {
            return originalFilename;
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public boolean isEmpty() {
            return content.length == 0;
        }

        @Override
        public long getSize() {
            return content.length;
        }

        @Override
        public byte[] getBytes() {
            return content.clone();
        }

        @Override
        public InputStream getInputStream() {
            return new java.io.ByteArrayInputStream(content);
        }

        @Override
        public void transferTo(File dest) throws IOException {
            Files.copy(getInputStream(), dest.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        @Override
        public void transferTo(Path dest) throws IOException {
            Files.copy(getInputStream(), dest,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
