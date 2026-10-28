package com.scholarclaw.modules.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.course.entity.CourseNamespaceEntity;
import com.scholarclaw.modules.course.service.CourseService;
import com.scholarclaw.modules.knowledge.dto.KnowledgeDocVO;
import com.scholarclaw.modules.userconfig.service.ModelConfigRules;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.multipart.MultipartFile;

/** TASK-803 OneBot 文件转内存 MultipartFile 后复用 Web 上传链路。 */
class KnowledgeUploadServiceBytesTest {

    @Test
    @DisplayName("uploadBytes：文件名/内容/大小正确传入统一上传流程")
    void uploadBytesDelegatesToUploadWithContent() {
        KnowledgeUploadService service = spy(service(mock(CourseService.class)));
        KnowledgeDocVO expected = doc();
        doReturn(expected).when(service).upload(eq(10001L), eq(2001L), any(MultipartFile.class));

        KnowledgeDocVO result = service.uploadBytes(10001L, 2001L, "a.pdf",
                "application/pdf", "Hello".getBytes(StandardCharsets.UTF_8));

        assertThat(result).isSameAs(expected);
        ArgumentCaptor<MultipartFile> file = ArgumentCaptor.forClass(MultipartFile.class);
        verify(service).upload(eq(10001L), eq(2001L), file.capture());
        assertThat(file.getValue().getOriginalFilename()).isEqualTo("a.pdf");
        assertThat(file.getValue().getContentType()).isEqualTo("application/pdf");
        assertThat(file.getValue().getSize()).isEqualTo(5L);
        assertThat(file.getValue().isEmpty()).isFalse();
    }

    @Test
    @DisplayName("uploadToDefaultCourse：懒加载默认课程后复用 uploadBytes，并返回课程名")
    void uploadsToDefaultCourse() {
        CourseService courseService = mock(CourseService.class);
        KnowledgeUploadService service = spy(service(courseService));
        CourseNamespaceEntity course = new CourseNamespaceEntity();
        course.setCourseId(2001L);
        course.setCourseName("未分类课程");
        when(courseService.getOrCreateDefaultCourse(10001L)).thenReturn(course);
        KnowledgeDocVO expected = doc();
        doReturn(expected).when(service).uploadBytes(eq(10001L), eq(2001L), eq("a.pdf"),
                eq("application/pdf"), any());

        KnowledgeUploadService.DefaultCourseUpload result = service.uploadToDefaultCourse(
                10001L, "a.pdf", "application/pdf",
                "Hello".getBytes(StandardCharsets.UTF_8));

        assertThat(result.doc()).isSameAs(expected);
        assertThat(result.courseName()).isEqualTo("未分类课程");
        verify(service).uploadBytes(eq(10001L), eq(2001L), eq("a.pdf"),
                eq("application/pdf"), any());
    }

    @Test
    @DisplayName("uploadBytes：空内容直接按文件大小错误拒绝")
    void rejectsEmptyContent() {
        KnowledgeUploadService service = service(mock(CourseService.class));

        assertThatThrownBy(() -> service.uploadBytes(10001L, 2001L, "a.pdf",
                "application/pdf", new byte[0]))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.FILE_SIZE_EXCEEDED));
    }

    private static KnowledgeUploadService service(CourseService courseService) {
        return new KnowledgeUploadService(
                mock(KnowledgeUploadCommitService.class),
                mock(KnowledgeIngestService.class),
                mock(KnowledgeDocService.class),
                courseService,
                mock(UserConfigService.class),
                mock(ModelConfigRules.class),
                mock(FileUploadSecurityValidator.class),
                mock(StorageService.class));
    }

    private static KnowledgeDocVO doc() {
        return new KnowledgeDocVO(50001L, 2001L, "a.pdf", "pdf", 5L,
                "PARSING", 1, 1, null, null, 0, 0, 0, "job-1",
                null, null, null, null, null, null);
    }
}
