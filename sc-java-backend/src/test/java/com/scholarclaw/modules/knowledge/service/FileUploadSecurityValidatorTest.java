package com.scholarclaw.modules.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

/** TASK-403 上传四重校验。 */
class FileUploadSecurityValidatorTest {

    @TempDir
    Path tempDir;

    private FileUploadSecurityValidator validator() {
        return new FileUploadSecurityValidator(new StorageService(tempDir.toString()));
    }

    @Test
    @DisplayName("合法 PDF：净化路径穿越文件名、计算 SHA-256 并写临时文件")
    void acceptsValidPdfAndSanitizesName() throws Exception {
        byte[] content = "%PDF-1.7\nhello".getBytes(StandardCharsets.UTF_8);
        MultipartFile file = new MockMultipartFile("file", "../../etc/passwd.pdf",
                "application/pdf", content);

        FileUploadSecurityValidator.ValidatedUpload result = validator().validateAndStage(file);

        assertThat(result.safeName()).isEqualTo("passwd.pdf");
        assertThat(result.extension()).isEqualTo(".pdf");
        assertThat(result.size()).isEqualTo(content.length);
        assertThat(result.sha256()).hasSize(64);
        assertThat(Files.exists(result.tempFile())).isTrue();
        Files.deleteIfExists(result.tempFile());
    }

    @Test
    @DisplayName("伪造 PDF（无 %PDF- 头）被拒绝")
    void rejectsFakePdf() {
        MultipartFile file = new MockMultipartFile("file", "fake.pdf",
                "application/pdf", "not a pdf".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> validator().validateAndStage(file))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.FILE_MAGIC_INVALID));
    }

    @Test
    @DisplayName("MD/TXT 必须是合法 UTF-8 且不含 NUL")
    void validatesTextEncoding() {
        assertThatThrownBy(() -> validator().validateAndStage(
                new MockMultipartFile("file", "bad.md", "text/markdown",
                        new byte[]{(byte) 0xC3, 0x28})))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.FILE_MAGIC_INVALID));

        assertThatThrownBy(() -> validator().validateAndStage(
                new MockMultipartFile("file", "bad.txt", "text/plain",
                        new byte[]{'a', 0x00, 'b'})))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.FILE_MAGIC_INVALID));
    }

    @Test
    @DisplayName("合法 UTF-8 文本通过")
    void acceptsValidText() {
        FileUploadSecurityValidator.ValidatedUpload result = validator().validateAndStage(
                new MockMultipartFile("file", "笔记.md", "text/markdown",
                        "第一章 事务".getBytes(StandardCharsets.UTF_8)));

        assertThat(result.extension()).isEqualTo(".md");
        assertThat(result.safeName()).isEqualTo("笔记.md");
    }

    @Test
    @DisplayName("非白名单扩展名被拒绝")
    void rejectsUnsupportedExtension() {
        assertThatThrownBy(() -> validator().validateAndStage(
                new MockMultipartFile("file", "a.exe", "application/octet-stream", new byte[]{1})))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.FILE_TYPE_UNSUPPORTED));
    }

    @Test
    @DisplayName("空文件与超过 50MB 被拒绝")
    void rejectsEmptyAndOversized() {
        assertThatThrownBy(() -> validator().validateAndStage(
                new MockMultipartFile("file", "a.pdf", "application/pdf", new byte[0])))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.FILE_SIZE_EXCEEDED));

        MultipartFile oversized = mock(MultipartFile.class);
        when(oversized.isEmpty()).thenReturn(false);
        when(oversized.getSize()).thenReturn(FileUploadSecurityValidator.MAX_FILE_SIZE_BYTES + 1);
        assertThatThrownBy(() -> validator().validateAndStage(oversized))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.FILE_SIZE_EXCEEDED));
    }

    @Test
    @DisplayName("反斜杠与 .. 都被剥离")
    void sanitizesBackslashesAndDotDot() {
        assertThat(FileUploadSecurityValidator.sanitizeFileName("..\\..\\evil.txt"))
                .isEqualTo("evil.txt");
    }
}
