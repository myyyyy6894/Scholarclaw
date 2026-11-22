package com.scholarclaw.modules.knowledge.service;

import com.scholarclaw.common.enums.DocumentStatus;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.course.service.CourseService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.Objects;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;

/** Authorizes access to stored originals without exposing a public storage path. */
@Service
public class KnowledgeFileService {
    private final CourseService courses;
    private final KnowledgeDocService documents;
    private final StorageService storage;

    public KnowledgeFileService(CourseService courses, KnowledgeDocService documents, StorageService storage) {
        this.courses = courses;
        this.documents = documents;
        this.storage = storage;
    }

    public record DocumentFile(String filename, FileSystemResource resource) { }

    public DocumentFile read(long userId, long courseId, long docId) {
        courses.getActiveCourse(userId, courseId);
        var doc = documents.requireOwnedDoc(userId, docId);
        String extension = doc.getFileExt();
        if (!Objects.equals(doc.getCourseId(), courseId)
                || DocumentStatus.DELETING.name().equals(doc.getStatus())
                || DocumentStatus.DELETED.name().equals(doc.getStatus())
                || !FileUploadSecurityValidator.supportsExtension(extension)
                || !storage.rawRelativePath(userId, courseId, docId, extension).equals(doc.getStorageRelPath())) {
            throw missing();
        }
        var path = storage.rawFile(userId, courseId, docId, extension);
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || !path.toRealPath().startsWith(storage.root().toRealPath()) || !Files.isReadable(path)) {
                throw missing();
            }
        } catch (IOException e) {
            throw missing();
        }
        return new DocumentFile(FileUploadSecurityValidator.sanitizeFileName(doc.getDocName()),
                new FileSystemResource(path));
    }

    private static BusinessException missing() {
        return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "找不到该课件原文件。");
    }
}
