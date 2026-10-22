package com.scholarclaw.modules.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scholarclaw.common.enums.IndexStatus;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.course.entity.CourseNamespaceEntity;
import com.scholarclaw.modules.course.service.CourseService;
import com.scholarclaw.modules.knowledge.dto.KnowledgeDocVO;
import com.scholarclaw.modules.knowledge.entity.KnowledgeDocEntity;
import com.scholarclaw.modules.knowledge.service.FileUploadSecurityValidator.ValidatedUpload;
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import com.scholarclaw.modules.userconfig.service.ModelConfigRules;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

/** TASK-403 上传编排：准入、事务委托与临时文件清理。 */
class KnowledgeUploadServiceTest {

    private static final long USER_ID = 10001L;
    private static final long COURSE_ID = 2001L;
    private static final long DOC_ID = 50001L;

    private KnowledgeUploadCommitService commitService;
    private KnowledgeIngestService ingestService;
    private KnowledgeDocService docService;
    private CourseService courseService;
    private UserConfigService userConfigService;
    private FileUploadSecurityValidator validator;
    private StorageService storageService;
    private KnowledgeUploadService service;
    private MultipartFile file;

    @BeforeEach
    void setUp() {
        commitService = mock(KnowledgeUploadCommitService.class);
        ingestService = mock(KnowledgeIngestService.class);
        docService = mock(KnowledgeDocService.class);
        courseService = mock(CourseService.class);
        userConfigService = mock(UserConfigService.class);
        validator = mock(FileUploadSecurityValidator.class);
        storageService = mock(StorageService.class);
        service = new KnowledgeUploadService(commitService, ingestService, docService,
                courseService, userConfigService, new ModelConfigRules(false, ""),
                validator, storageService);
        file = mock(MultipartFile.class);
    }

    @Test
    @DisplayName("Embedding 未配置时拒绝上传，且不触碰文件")
    void rejectsWhenEmbeddingInvalid() {
        UserConfigEntity config = validConfig();
        config.setDefaultEmbeddingDim(null);
        when(userConfigService.findById(USER_ID)).thenReturn(Optional.of(config));

        assertThatThrownBy(() -> service.upload(USER_ID, COURSE_ID, file))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.E_SYS_01));
        verify(validator, never()).validateAndStage(any());
    }

    @Test
    @DisplayName("课程 REBUILDING 时快速失败，不读取文件")
    void rejectsWhenCourseRebuilding() {
        when(userConfigService.findById(USER_ID)).thenReturn(Optional.of(validConfig()));
        when(courseService.getActiveCourse(USER_ID, COURSE_ID))
                .thenReturn(course(IndexStatus.REBUILDING));

        assertThatThrownBy(() -> service.upload(USER_ID, COURSE_ID, file))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.E_SYS_03));
        verify(validator, never()).validateAndStage(any());
    }

    @Test
    @DisplayName("成功后委托事务服务，并清理临时文件")
    void delegatesCommitAndCleansTemp() {
        Path temp = Path.of("/tmp/upload.tmp");
        when(userConfigService.findById(USER_ID)).thenReturn(Optional.of(validConfig()));
        when(courseService.getActiveCourse(USER_ID, COURSE_ID)).thenReturn(course(IndexStatus.ACTIVE));
        ValidatedUpload staged = new ValidatedUpload("a.pdf", ".pdf", 10, "sha", temp);
        when(validator.validateAndStage(file)).thenReturn(staged);
        when(commitService.commit(USER_ID, COURSE_ID, staged)).thenReturn(sampleVo());
        when(docService.requireOwnedDoc(USER_ID, DOC_ID)).thenReturn(doc());
        when(docService.toVO(any())).thenReturn(sampleVo());

        KnowledgeDocVO result = service.upload(USER_ID, COURSE_ID, file);

        assertThat(result.docId()).isEqualTo(DOC_ID);
        assertThat(result.status()).isEqualTo("UPLOADED");
        verify(commitService).commit(USER_ID, COURSE_ID, staged);
        verify(ingestService).enqueueInitial(USER_ID, DOC_ID);
        verify(storageService).deleteQuietly(temp);
    }

    @Test
    @DisplayName("事务失败时仍清理临时文件并向上抛出业务错误")
    void cleansTempWhenCommitFails() {
        Path temp = Path.of("/tmp/upload.tmp");
        when(userConfigService.findById(USER_ID)).thenReturn(Optional.of(validConfig()));
        when(courseService.getActiveCourse(USER_ID, COURSE_ID)).thenReturn(course(IndexStatus.ACTIVE));
        ValidatedUpload staged = new ValidatedUpload("a.pdf", ".pdf", 10, "sha", temp);
        when(validator.validateAndStage(file)).thenReturn(staged);
        when(commitService.commit(USER_ID, COURSE_ID, staged))
                .thenThrow(new BusinessException(ErrorCode.DOC_DUPLICATE_SHA256));

        assertThatThrownBy(() -> service.upload(USER_ID, COURSE_ID, file))
                .isInstanceOf(BusinessException.class);
        verify(ingestService, never()).enqueueInitial(any(), any());
        verify(storageService).deleteQuietly(temp);
    }

    private static UserConfigEntity validConfig() {
        UserConfigEntity config = new UserConfigEntity();
        config.setUserId(USER_ID);
        config.setAccountStatus("ACTIVE");
        config.setDefaultEmbeddingProvider("OPENAI_COMPATIBLE");
        config.setEmbeddingBaseUrl("https://api.siliconflow.cn/v1");
        config.setDefaultEmbeddingModel("BAAI/bge-m3");
        config.setDefaultEmbeddingDim(1024);
        config.setEmbeddingApiKeyCipher("v1:iv:cipher");
        return config;
    }

    private static CourseNamespaceEntity course(IndexStatus status) {
        CourseNamespaceEntity course = new CourseNamespaceEntity();
        course.setCourseId(COURSE_ID);
        course.setUserId(USER_ID);
        course.setIndexVersion(1);
        course.setIndexStatus(status.name());
        course.setIsDeleting(0);
        return course;
    }

    private static KnowledgeDocEntity doc() {
        KnowledgeDocEntity doc = new KnowledgeDocEntity();
        doc.setDocId(DOC_ID);
        doc.setUserId(USER_ID);
        doc.setCourseId(COURSE_ID);
        doc.setDocName("a.pdf");
        doc.setFileExt(".pdf");
        doc.setFileSizeBytes(10L);
        doc.setStatus("UPLOADED");
        doc.setParseVersion(1);
        doc.setCourseIndexVersion(1);
        doc.setStorageRelPath("raw/10001/2001/50001.pdf");
        doc.setFileSha256("sha");
        return doc;
    }

    private static KnowledgeDocVO sampleVo() {
        return new KnowledgeDocVO(DOC_ID, COURSE_ID, "a.pdf", ".pdf", 10, "UPLOADED",
                1, 1, null, null, 0, 0, 0, null, null, null, null, null,
                "2026-10-21T10:00:00.000+08:00", "2026-10-21T10:00:00.000+08:00");
    }
}
