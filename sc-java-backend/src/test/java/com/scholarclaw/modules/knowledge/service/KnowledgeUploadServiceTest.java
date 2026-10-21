package com.scholarclaw.modules.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
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
import com.scholarclaw.modules.knowledge.mapper.KnowledgeDocMapper;
import com.scholarclaw.modules.knowledge.service.FileUploadSecurityValidator.ValidatedUpload;
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import com.scholarclaw.modules.userconfig.service.ModelConfigRules;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

/** TASK-403 上传编排：准入、查重、落盘与失败补偿。 */
class KnowledgeUploadServiceTest {

    private static final long USER_ID = 10001L;
    private static final long COURSE_ID = 2001L;
    private static final long DOC_ID = 50001L;

    private KnowledgeDocMapper docMapper;
    private KnowledgeDocService docService;
    private CourseService courseService;
    private UserConfigService userConfigService;
    private FileUploadSecurityValidator validator;
    private StorageService storageService;
    private KnowledgeUploadService service;
    private MultipartFile file;

    @BeforeEach
    void setUp() {
        docMapper = mock(KnowledgeDocMapper.class);
        docService = mock(KnowledgeDocService.class);
        courseService = mock(CourseService.class);
        userConfigService = mock(UserConfigService.class);
        validator = mock(FileUploadSecurityValidator.class);
        storageService = mock(StorageService.class);
        service = new KnowledgeUploadService(docMapper, docService, courseService,
                userConfigService, new ModelConfigRules(false, ""), validator, storageService);
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
    @DisplayName("课程 REBUILDING 时拒绝上传")
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
    @DisplayName("同课程相同 SHA-256 被拒绝，并删除临时文件")
    void rejectsDuplicateAndCleansTemp() {
        Path temp = Path.of("/tmp/upload.tmp");
        when(userConfigService.findById(USER_ID)).thenReturn(Optional.of(validConfig()));
        when(courseService.getActiveCourse(USER_ID, COURSE_ID)).thenReturn(course(IndexStatus.ACTIVE));
        when(validator.validateAndStage(file))
                .thenReturn(new ValidatedUpload("a.pdf", ".pdf", 10, "sha", temp));
        when(docMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.upload(USER_ID, COURSE_ID, file))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.DOC_DUPLICATE_SHA256));
        verify(storageService).deleteQuietly(temp);
        verify(docService, never()).createDocumentMetadata(
                anyLong(), anyLong(), any(), any(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("成功后写元数据、移动文件并回填相对路径")
    void uploadsAndMovesFile() {
        Path temp = Path.of("/tmp/upload.tmp");
        when(userConfigService.findById(USER_ID)).thenReturn(Optional.of(validConfig()));
        when(courseService.getActiveCourse(USER_ID, COURSE_ID)).thenReturn(course(IndexStatus.ACTIVE));
        when(validator.validateAndStage(file))
                .thenReturn(new ValidatedUpload("a.pdf", ".pdf", 10, "sha", temp));
        when(docMapper.selectCount(any())).thenReturn(0L);
        when(docService.createDocumentMetadata(USER_ID, COURSE_ID, "a.pdf", ".pdf",
                10, "sha", "pending")).thenReturn(doc());
        when(storageService.moveToRaw(temp, USER_ID, COURSE_ID, DOC_ID, ".pdf"))
                .thenReturn("raw/10001/2001/50001.pdf");
        when(docService.toVO(any())).thenReturn(sampleVo());

        KnowledgeDocVO result = service.upload(USER_ID, COURSE_ID, file);

        assertThat(result.docId()).isEqualTo(DOC_ID);
        assertThat(result.status()).isEqualTo("UPLOADED");
        verify(docService).updateStorageRelPath(USER_ID, DOC_ID, "raw/10001/2001/50001.pdf");
    }

    @Test
    @DisplayName("落盘失败时补偿删除未提交元数据与目标文件")
    void compensatesWhenMoveFails() {
        Path temp = Path.of("/tmp/upload.tmp");
        when(userConfigService.findById(USER_ID)).thenReturn(Optional.of(validConfig()));
        when(courseService.getActiveCourse(USER_ID, COURSE_ID)).thenReturn(course(IndexStatus.ACTIVE));
        when(validator.validateAndStage(file))
                .thenReturn(new ValidatedUpload("a.pdf", ".pdf", 10, "sha", temp));
        when(docMapper.selectCount(any())).thenReturn(0L);
        when(docService.createDocumentMetadata(anyLong(), anyLong(), any(), any(), anyLong(), any(), any()))
                .thenReturn(doc());
        when(storageService.moveToRaw(any(), anyLong(), anyLong(), anyLong(), any()))
                .thenThrow(new IllegalStateException("disk full"));

        assertThatThrownBy(() -> service.upload(USER_ID, COURSE_ID, file))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INTERNAL_ERROR));
        verify(docService).deleteUncommittedMetadata(USER_ID, DOC_ID);
        verify(storageService).deleteRawQuietly(USER_ID, COURSE_ID, DOC_ID, ".pdf");
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
        doc.setCreatedAt(LocalDateTime.now());
        doc.setUpdatedAt(LocalDateTime.now());
        return doc;
    }

    private static KnowledgeDocVO sampleVo() {
        return new KnowledgeDocVO(DOC_ID, COURSE_ID, "a.pdf", ".pdf", 10, "UPLOADED",
                1, 1, null, null, 0, 0, 0, null, null, null, null,
                "2026-10-21T10:00:00.000+08:00", "2026-10-21T10:00:00.000+08:00");
    }
}
