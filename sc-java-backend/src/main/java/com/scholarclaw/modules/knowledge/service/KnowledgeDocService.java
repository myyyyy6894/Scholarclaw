package com.scholarclaw.modules.knowledge.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.scholarclaw.common.config.ScTimeZone;
import com.scholarclaw.common.enums.DocumentStatus;
import com.scholarclaw.common.enums.IndexStatus;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.course.entity.CourseNamespaceEntity;
import com.scholarclaw.modules.course.service.CourseService;
import com.scholarclaw.modules.knowledge.dto.KnowledgeDocVO;
import com.scholarclaw.modules.knowledge.entity.KnowledgeDocEntity;
import com.scholarclaw.modules.knowledge.mapper.KnowledgeDocMapper;
import com.scholarclaw.modules.knowledge.mapper.KnowledgeParentChunkMapper;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知识库文档元数据与状态机（{@code 06} TASK-402、{@code 02} §9.1、{@code 04} §4.4）。
 *
 * <p>所有状态更新都带 {@code parse_version}（必要时还带 {@code course_index_version}）条件，
 * 旧解析任务的迟到回调只能得到 {@code false}，绝不能覆盖新版本结果。
 * 本服务不直接发 MQ（TASK-501）也不直接操作 ChromaDB（TASK-901）。
 */
@Service
public class KnowledgeDocService {

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
    private static final Set<String> LISTABLE_STATUSES = Set.of(
            DocumentStatus.UPLOADED.name(), DocumentStatus.PARSING.name(),
            DocumentStatus.READY.name(), DocumentStatus.FAILED.name());

    private final KnowledgeDocMapper docMapper;
    private final KnowledgeParentChunkMapper parentChunkMapper;
    private final CourseService courseService;

    public KnowledgeDocService(KnowledgeDocMapper docMapper,
                               KnowledgeParentChunkMapper parentChunkMapper,
                               CourseService courseService) {
        this.docMapper = docMapper;
        this.parentChunkMapper = parentChunkMapper;
        this.courseService = courseService;
    }

    /** `API-DOC-001`：查询课程下未彻底删除的文档；可选按四种活动状态过滤。 */
    public List<KnowledgeDocVO> listDocs(Long userId, Long courseId, String status) {
        courseService.getActiveCourse(userId, courseId);
        var query = Wrappers.<KnowledgeDocEntity>lambdaQuery()
                .eq(KnowledgeDocEntity::getUserId, userId)
                .eq(KnowledgeDocEntity::getCourseId, courseId);
        if (status == null || status.isBlank()) {
            query.notIn(KnowledgeDocEntity::getStatus,
                    DocumentStatus.DELETING.name(), DocumentStatus.DELETED.name());
        } else {
            String normalized = status.trim().toUpperCase(Locale.ROOT);
            if (!LISTABLE_STATUSES.contains(normalized)) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "status 仅允许 UPLOADED/PARSING/READY/FAILED。");
            }
            query.eq(KnowledgeDocEntity::getStatus, normalized);
        }
        query.orderByDesc(KnowledgeDocEntity::getCreatedAt);
        ZoneId zone = ScTimeZone.zoneId();
        return docMapper.selectList(query).stream().map(doc -> toVO(doc, zone)).toList();
    }

    /**
     * 插入文档元数据（上传流程在 TASK-403 调用）。
     *
     * <p>`storage_rel_path` 可先传占位值，文件移动完成后用 {@link #updateStorageRelPath} 回填。
     */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeDocEntity createDocumentMetadata(Long userId, Long courseId, String docName,
                                                     String fileExt, long fileSizeBytes,
                                                     String fileSha256, String storageRelPath) {
        CourseNamespaceEntity course = courseService.getActiveCourse(userId, courseId);
        if (IndexStatus.REBUILDING.name().equals(course.getIndexStatus())) {
            throw new BusinessException(ErrorCode.E_SYS_03,
                    "当前课程索引正在重建中，请等待完成后再上传。");
        }
        LocalDateTime now = ScTimeZone.now().toLocalDateTime();
        KnowledgeDocEntity entity = new KnowledgeDocEntity();
        entity.setUserId(userId);
        entity.setCourseId(courseId);
        entity.setDocName(docName);
        entity.setFileExt(fileExt.toLowerCase(Locale.ROOT));
        entity.setFileSizeBytes(fileSizeBytes);
        entity.setFileSha256(fileSha256);
        entity.setStorageRelPath(storageRelPath);
        entity.setStatus(DocumentStatus.UPLOADED.name());
        entity.setParseVersion(1);
        entity.setCourseIndexVersion(course.getIndexVersion());
        entity.setPageCount(0);
        entity.setChunkCount(0);
        entity.setOcrUsed(0);
        entity.setRetryCount(0);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        try {
            docMapper.insert(entity);
            return entity;
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.DOC_DUPLICATE_SHA256);
        }
    }

    /** 文件移动完成后回填真实相对路径（仅更新本字段）。 */
    @Transactional(rollbackFor = Exception.class)
    public void updateStorageRelPath(Long userId, Long docId, String storageRelPath) {
        int affected = docMapper.update(null, Wrappers.<KnowledgeDocEntity>lambdaUpdate()
                .eq(KnowledgeDocEntity::getDocId, docId)
                .eq(KnowledgeDocEntity::getUserId, userId)
                .eq(KnowledgeDocEntity::getStatus, DocumentStatus.UPLOADED.name())
                .set(KnowledgeDocEntity::getStorageRelPath, storageRelPath)
                .set(KnowledgeDocEntity::getUpdatedAt, ScTimeZone.now().toLocalDateTime()));
        if (affected != 1) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT,
                    "文档状态已变化，无法回填存储路径。");
        }
    }

    /** `UPLOADED -> PARSING`：仅匹配指定 parse_version。返回 false 表示已被并发推进/删除。 */
    @Transactional(rollbackFor = Exception.class)
    public boolean markParsing(Long docId, int parseVersion) {
        return docMapper.update(null, Wrappers.<KnowledgeDocEntity>lambdaUpdate()
                .eq(KnowledgeDocEntity::getDocId, docId)
                .eq(KnowledgeDocEntity::getParseVersion, parseVersion)
                .eq(KnowledgeDocEntity::getStatus, DocumentStatus.UPLOADED.name())
                .set(KnowledgeDocEntity::getStatus, DocumentStatus.PARSING.name())
                .set(KnowledgeDocEntity::getUpdatedAt, ScTimeZone.now().toLocalDateTime())) == 1;
    }

    /**
     * `PARSING -> READY`：必须同时匹配 parse_version 与 course_index_version。
     * 返回 false 表示回调已过期，调用方不得写入父块。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean markReady(Long docId, int parseVersion, int courseIndexVersion,
                             int pageCount, int chunkCount,
                             String embeddingProvider, String embeddingModel, int embeddingDim) {
        LocalDateTime now = ScTimeZone.now().toLocalDateTime();
        return docMapper.update(null, Wrappers.<KnowledgeDocEntity>lambdaUpdate()
                .eq(KnowledgeDocEntity::getDocId, docId)
                .eq(KnowledgeDocEntity::getParseVersion, parseVersion)
                .eq(KnowledgeDocEntity::getCourseIndexVersion, courseIndexVersion)
                .eq(KnowledgeDocEntity::getStatus, DocumentStatus.PARSING.name())
                .set(KnowledgeDocEntity::getStatus, DocumentStatus.READY.name())
                .set(KnowledgeDocEntity::getPageCount, pageCount)
                .set(KnowledgeDocEntity::getChunkCount, chunkCount)
                .set(KnowledgeDocEntity::getIndexedEmbeddingProvider, embeddingProvider)
                .set(KnowledgeDocEntity::getIndexedEmbeddingModel, embeddingModel)
                .set(KnowledgeDocEntity::getIndexedEmbeddingDim, embeddingDim)
                .set(KnowledgeDocEntity::getReadyAt, now)
                .set(KnowledgeDocEntity::getUpdatedAt, now)) == 1;
    }

    /** `PARSING/UPLOADED -> FAILED`：旧版本失败回调返回 false。 */
    @Transactional(rollbackFor = Exception.class)
    public boolean markFailed(Long docId, int parseVersion, String errorCode, String errorMsg) {
        return docMapper.update(null, Wrappers.<KnowledgeDocEntity>lambdaUpdate()
                .eq(KnowledgeDocEntity::getDocId, docId)
                .eq(KnowledgeDocEntity::getParseVersion, parseVersion)
                .in(KnowledgeDocEntity::getStatus,
                        DocumentStatus.UPLOADED.name(), DocumentStatus.PARSING.name())
                .set(KnowledgeDocEntity::getStatus, DocumentStatus.FAILED.name())
                .set(KnowledgeDocEntity::getErrorCode, errorCode)
                .set(KnowledgeDocEntity::getErrorMsg, errorMsg)
                .set(KnowledgeDocEntity::getUpdatedAt, ScTimeZone.now().toLocalDateTime())) == 1;
    }

    /** `FAILED -> PARSING`：供 `API-DOC-003`/TASK-501 使用；MQ 发布由后续任务补齐。 */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeDocEntity retryFailed(Long userId, Long docId) {
        return transitionToParsing(userId, docId, DocumentStatus.FAILED,
                "仅解析失败(FAILED)状态的课件可执行手动重试。");
    }

    /** `READY -> PARSING`：供 `API-DOC-004`/TASK-501 使用。 */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeDocEntity reindexReady(Long userId, Long docId) {
        return transitionToParsing(userId, docId, DocumentStatus.READY,
                "文档当前非就绪状态，请勿重复操作。");
    }

    /** `非 DELETED -> DELETING`：同一事务内清空父块；重复删除幂等。 */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeDocEntity softDelete(Long userId, Long docId) {
        KnowledgeDocEntity doc = requireOwnedDoc(userId, docId);
        if (DocumentStatus.DELETING.name().equals(doc.getStatus())) {
            return doc;
        }
        if (DocumentStatus.DELETED.name().equals(doc.getStatus())) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        DocumentStateMachine.requireTransition(doc.getStatus(), DocumentStatus.DELETING);
        int affected = docMapper.update(null, Wrappers.<KnowledgeDocEntity>lambdaUpdate()
                .eq(KnowledgeDocEntity::getDocId, docId)
                .eq(KnowledgeDocEntity::getUserId, userId)
                .ne(KnowledgeDocEntity::getStatus, DocumentStatus.DELETED.name())
                .set(KnowledgeDocEntity::getStatus, DocumentStatus.DELETING.name())
                .setSql("parse_version = parse_version + 1")
                .set(KnowledgeDocEntity::getUpdatedAt, ScTimeZone.now().toLocalDateTime()));
        if (affected != 1) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT);
        }
        parentChunkMapper.deleteByDoc(userId, docId);
        return requireOwnedDoc(userId, docId);
    }

    /** RAG 有效文档集：`READY` 且课程索引版本匹配。 */
    public List<Long> validDocIds(Long userId, Long courseId, int courseIndexVersion) {
        return docMapper.selectList(Wrappers.<KnowledgeDocEntity>lambdaQuery()
                        .eq(KnowledgeDocEntity::getUserId, userId)
                        .eq(KnowledgeDocEntity::getCourseId, courseId)
                        .eq(KnowledgeDocEntity::getStatus, DocumentStatus.READY.name())
                        .eq(KnowledgeDocEntity::getCourseIndexVersion, courseIndexVersion)
                        .select(KnowledgeDocEntity::getDocId))
                .stream().map(KnowledgeDocEntity::getDocId).toList();
    }

    public KnowledgeDocEntity requireOwnedDoc(Long userId, Long docId) {
        KnowledgeDocEntity doc = docMapper.selectOne(Wrappers.<KnowledgeDocEntity>lambdaQuery()
                .eq(KnowledgeDocEntity::getDocId, docId)
                .eq(KnowledgeDocEntity::getUserId, userId));
        if (doc == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return doc;
    }

    private KnowledgeDocEntity transitionToParsing(Long userId, Long docId,
                                                   DocumentStatus requiredStatus, String message) {
        KnowledgeDocEntity doc = requireOwnedDoc(userId, docId);
        if (!requiredStatus.name().equals(doc.getStatus())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, message);
        }
        CourseNamespaceEntity course = courseService.getActiveCourse(userId, doc.getCourseId());
        int affected = docMapper.update(null, Wrappers.<KnowledgeDocEntity>lambdaUpdate()
                .eq(KnowledgeDocEntity::getDocId, docId)
                .eq(KnowledgeDocEntity::getUserId, userId)
                .eq(KnowledgeDocEntity::getStatus, requiredStatus.name())
                .set(KnowledgeDocEntity::getStatus, DocumentStatus.PARSING.name())
                .set(KnowledgeDocEntity::getCourseIndexVersion, course.getIndexVersion())
                .set(KnowledgeDocEntity::getRetryCount, 0)
                .set(KnowledgeDocEntity::getErrorCode, null)
                .set(KnowledgeDocEntity::getErrorMsg, null)
                .set(KnowledgeDocEntity::getEnqueuedAt, ScTimeZone.now().toLocalDateTime())
                .set(KnowledgeDocEntity::getUpdatedAt, ScTimeZone.now().toLocalDateTime())
                .setSql("parse_version = parse_version + 1"));
        if (affected != 1) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, message);
        }
        return requireOwnedDoc(userId, docId);
    }

    private static KnowledgeDocVO toVO(KnowledgeDocEntity doc, ZoneId zone) {
        return new KnowledgeDocVO(
                doc.getDocId(),
                doc.getCourseId(),
                doc.getDocName(),
                doc.getFileExt(),
                doc.getFileSizeBytes() == null ? 0 : doc.getFileSizeBytes(),
                doc.getStatus(),
                doc.getParseVersion() == null ? 1 : doc.getParseVersion(),
                doc.getCourseIndexVersion() == null ? 1 : doc.getCourseIndexVersion(),
                doc.getIndexedEmbeddingModel(),
                doc.getIndexedEmbeddingDim(),
                doc.getPageCount() == null ? 0 : doc.getPageCount(),
                doc.getChunkCount() == null ? 0 : doc.getChunkCount(),
                doc.getRetryCount() == null ? 0 : doc.getRetryCount(),
                doc.getErrorCode(),
                doc.getErrorMsg(),
                formatTimestamp(doc.getEnqueuedAt(), zone),
                formatTimestamp(doc.getReadyAt(), zone),
                formatTimestamp(doc.getCreatedAt(), zone),
                formatTimestamp(doc.getUpdatedAt(), zone));
    }

    private static String formatTimestamp(LocalDateTime value, ZoneId zone) {
        return value == null ? null : value.atZone(zone).toOffsetDateTime().format(TIMESTAMP_FORMAT);
    }
}
