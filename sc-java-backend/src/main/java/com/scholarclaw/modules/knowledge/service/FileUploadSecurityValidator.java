package com.scholarclaw.modules.knowledge.service;

import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * 上传文件安全校验（{@code NFR-SEC-04}、{@code 04} §4.4.2 业务规则 2）。
 *
 * <p>顺序：大小 → 文件名净化/扩展名 → Magic Number / UTF-8 → 写入临时文件并计算 SHA-256。
 * 任何一步失败都抛确定性错误码，临时文件由调用方清理。
 */
@Component
public class FileUploadSecurityValidator {

    static final long MAX_FILE_SIZE_BYTES = 52_428_800L;
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".pdf", ".md", ".txt");
    private static final int MAX_FILE_NAME_LENGTH = 255;
    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};

    private final StorageService storageService;

    public FileUploadSecurityValidator(StorageService storageService) {
        this.storageService = storageService;
    }

    public ValidatedUpload validateAndStage(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() <= 0 || file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new BusinessException(ErrorCode.FILE_SIZE_EXCEEDED);
        }
        String originalName = file.getOriginalFilename();
        String safeName = sanitizeFileName(originalName);
        String extension = extensionOf(safeName);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BusinessException(ErrorCode.FILE_TYPE_UNSUPPORTED);
        }
        validateContent(file, extension);

        Path temp = null;
        try {
            temp = storageService.createTempFile();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = new DigestInputStream(file.getInputStream(), digest)) {
                Files.copy(input, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return new ValidatedUpload(safeName, extension, file.getSize(),
                    HexFormat.of().formatHex(digest.digest()), temp);
        } catch (IOException | NoSuchAlgorithmException e) {
            if (temp != null) {
                storageService.deleteQuietly(temp);
            }
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "课件保存失败，请稍后重试。");
        }
    }

    /** 剥离路径分隔符、`..` 与控制字符，保留安全基本文件名并截断到 255 字符。 */
    static String sanitizeFileName(String original) {
        if (original == null) {
            return "upload";
        }
        String normalized = original.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0) {
            normalized = normalized.substring(slash + 1);
        }
        normalized = normalized.replace("..", "")
                .replaceAll("[\\p{Cntrl}]", "")
                .trim();
        if (normalized.isEmpty()) {
            return "upload";
        }
        if (normalized.length() > MAX_FILE_NAME_LENGTH) {
            normalized = normalized.substring(normalized.length() - MAX_FILE_NAME_LENGTH);
        }
        return normalized;
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) {
            throw new BusinessException(ErrorCode.FILE_TYPE_UNSUPPORTED);
        }
        return fileName.substring(dot).toLowerCase(Locale.ROOT);
    }

    private static void validateContent(MultipartFile file, String extension) {
        if (".pdf".equals(extension)) {
            byte[] head = new byte[PDF_MAGIC.length];
            try (var input = file.getInputStream()) {
                int read = input.readNBytes(head, 0, head.length);
                if (read != PDF_MAGIC.length) {
                    throw new BusinessException(ErrorCode.FILE_MAGIC_INVALID);
                }
            } catch (IOException e) {
                throw new BusinessException(ErrorCode.FILE_MAGIC_INVALID);
            }
            for (int i = 0; i < PDF_MAGIC.length; i++) {
                if (head[i] != PDF_MAGIC[i]) {
                    throw new BusinessException(ErrorCode.FILE_MAGIC_INVALID);
                }
            }
            return;
        }
        // MD/TXT：必须能按 UTF-8 无损解码，且不含二进制零字节
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (Reader reader = new InputStreamReader(file.getInputStream(), decoder)) {
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                for (int i = 0; i < read; i++) {
                    if (buffer[i] == '\u0000') {
                        throw new BusinessException(ErrorCode.FILE_MAGIC_INVALID);
                    }
                }
            }
        } catch (CharacterCodingException e) {
            throw new BusinessException(ErrorCode.FILE_MAGIC_INVALID);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.FILE_MAGIC_INVALID);
        }
    }

    /** 校验通过并已写入临时文件的元数据。 */
    public record ValidatedUpload(String safeName, String extension, long size,
                                  String sha256, Path tempFile) {
    }
}
