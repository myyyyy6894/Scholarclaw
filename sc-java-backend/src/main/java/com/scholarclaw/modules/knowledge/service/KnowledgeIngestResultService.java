package com.scholarclaw.modules.knowledge.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.scholarclaw.common.config.ScTimeZone;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.course.entity.CourseNamespaceEntity;
import com.scholarclaw.modules.course.service.CourseService;
import com.scholarclaw.modules.knowledge.dto.IngestResultRequest;
import com.scholarclaw.modules.knowledge.dto.IngestResultVO;
import com.scholarclaw.modules.knowledge.entity.KnowledgeDocEntity;
import com.scholarclaw.modules.knowledge.entity.KnowledgeParentChunkEntity;
import com.scholarclaw.modules.knowledge.mapper.KnowledgeDocMapper;
import com.scholarclaw.modules.knowledge.mapper.KnowledgeParentChunkMapper;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * `API-INT-JAVA-003`：解析结果回调（TASK-503）。
 *
 * <p>单一本地事务内完成：`parse_version + course_index_version + status='PARSING'` 条件更新、
 * 父块先删后插。旧版本/重复回调返回 `applied=false` 且不写父块。
 */
@Service
public class KnowledgeIngestResultService {

    private static final Pattern ERROR_CODE = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final int MAX_PARENT_CHUNKS = 5000;
    private static final int MAX_PARENT_CONTENT = 20_000;

    private final KnowledgeDocMapper docMapper;
    private final KnowledgeParentChunkMapper parentChunkMapper;
    private final KnowledgeDocService docService;
    private final CourseService courseService;

    public KnowledgeIngestResultService(KnowledgeDocMapper docMapper,
                                        KnowledgeParentChunkMapper parentChunkMapper,
                                        KnowledgeDocService docService,
                                        CourseService courseService) {
        this.docMapper = docMapper;
        this.parentChunkMapper = parentChunkMapper;
        this.docService = docService;
        this.courseService = courseService;
    }

    @Transactional(rollbackFor = Exception.class)
    public IngestResultVO apply(Long headerUserId, Long docId, IngestResultRequest request) {
        requireIdentity(headerUserId, request);
        KnowledgeDocEntity doc = docMapper.selectById(docId);
        if (doc == null || !doc.getUserId().equals(headerUserId)
                || !doc.getCourseId().equals(parseLong(request.courseId(), "course_id"))) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        String status = request.status().trim().toUpperCase();
        if (!"READY".equals(status) && !"FAILED".equals(status)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "status 仅允许 READY 或 FAILED。");
        }
        if (!doc.getParseVersion().equals(request.parseVersion())
                || !doc.getCourseIndexVersion().equals(request.courseIndexVersion())
                || !"PARSING".equals(doc.getStatus())) {
            return result(docId, false);
        }

        if ("FAILED".equals(status)) {
            boolean applied = docService.markFailed(docId, request.parseVersion(),
                    normalizeErrorCode(request.errorCode()), normalizeErrorMsg(request.errorMsg()));
            if (applied) {
                refreshCourse(doc, request.courseIndexVersion());
            }
            return result(docId, applied);
        }

        CourseNamespaceEntity course = courseService.getActiveCourse(headerUserId, doc.getCourseId());
        validateReadyRequest(course, docId, request);
        LocalDateTime now = ScTimeZone.now().toLocalDateTime();
        int affected = docMapper.update(null, Wrappers.<KnowledgeDocEntity>lambdaUpdate()
                .eq(KnowledgeDocEntity::getDocId, docId)
                .eq(KnowledgeDocEntity::getUserId, headerUserId)
                .eq(KnowledgeDocEntity::getParseVersion, request.parseVersion())
                .eq(KnowledgeDocEntity::getCourseIndexVersion, request.courseIndexVersion())
                .eq(KnowledgeDocEntity::getStatus, "PARSING")
                .set(KnowledgeDocEntity::getStatus, "READY")
                .set(KnowledgeDocEntity::getPageCount, request.pageCount())
                .set(KnowledgeDocEntity::getChunkCount, request.chunkCount())
                .set(KnowledgeDocEntity::getIndexedEmbeddingProvider,
                        request.indexedEmbeddingProvider())
                .set(KnowledgeDocEntity::getIndexedEmbeddingModel, request.indexedEmbeddingModel())
                .set(KnowledgeDocEntity::getIndexedEmbeddingDim, request.indexedEmbeddingDim())
                .set(KnowledgeDocEntity::getLastIngestJobId, request.ingestJobId())
                .set(KnowledgeDocEntity::getReadyAt, now)
                .set(KnowledgeDocEntity::getUpdatedAt, now)
                .set(KnowledgeDocEntity::getErrorCode, null)
                .set(KnowledgeDocEntity::getErrorMsg, null));
        if (affected != 1) {
            return result(docId, false);
        }

        parentChunkMapper.deleteByDoc(headerUserId, docId);
        for (IngestResultRequest.ParentChunkItem item : request.parentChunks()) {
            parentChunkMapper.insert(toEntity(headerUserId, doc.getCourseId(), docId,
                    request.parseVersion(), item));
        }
        refreshCourse(doc, request.courseIndexVersion());
        return result(docId, true);
    }

    private void refreshCourse(KnowledgeDocEntity doc, int indexVersion) {
        // 与文档/父块同一事务：失败则整体回滚，由 Python 重试回调，避免状态分叉
        courseService.refreshIndexStatus(doc.getUserId(), doc.getCourseId(), indexVersion);
    }

    private void validateReadyRequest(CourseNamespaceEntity course, Long docId,
                                      IngestResultRequest request) {
        if (request.pageCount() < 1 || request.chunkCount() < 1) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "READY 回调的 page_count/chunk_count 必须 >= 1。");
        }
        if (!course.getEmbeddingProvider().equals(request.indexedEmbeddingProvider())
                || !course.getEmbeddingModel().equals(request.indexedEmbeddingModel())
                || !course.getEmbeddingDim().equals(request.indexedEmbeddingDim())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "indexed_embedding_* 与课程锁定配置不一致。");
        }
        List<IngestResultRequest.ParentChunkItem> chunks = request.parentChunks();
        if (chunks == null || chunks.isEmpty() || chunks.size() > MAX_PARENT_CHUNKS) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "parent_chunks 数量非法。");
        }
        Set<String> seen = new HashSet<>();
        for (IngestResultRequest.ParentChunkItem item : chunks) {
            String expected = "pc_" + docId + "_v" + request.parseVersion() + "_" + item.chunkSeq();
            if (!expected.equals(item.parentChunkId()) || !seen.add(item.parentChunkId())) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "parent_chunk_id 与 seq 不匹配或重复。");
            }
            if (item.pageEnd() < item.pageStart() || item.pageEnd() > request.pageCount()) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "父块页码区间非法。");
            }
            String content = item.content();
            if (content == null || content.isBlank() || content.length() > MAX_PARENT_CONTENT) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "父块内容为空或过长。");
            }
            if (item.sectionTitle() != null && item.sectionTitle().length() > 255) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "父块标题过长。");
            }
        }
    }

    private KnowledgeParentChunkEntity toEntity(Long userId, Long courseId, Long docId,
                                                int parseVersion,
                                                IngestResultRequest.ParentChunkItem item) {
        KnowledgeParentChunkEntity entity = new KnowledgeParentChunkEntity();
        entity.setParentChunkId(item.parentChunkId());
        entity.setDocId(docId);
        entity.setUserId(userId);
        entity.setCourseId(courseId);
        entity.setParseVersion(parseVersion);
        entity.setChunkSeq(item.chunkSeq());
        entity.setPageStart(item.pageStart());
        entity.setPageEnd(item.pageEnd());
        entity.setSectionTitle(item.sectionTitle());
        entity.setContent(item.content());
        entity.setCharLength(item.content().length());
        entity.setCreatedAt(ScTimeZone.now().toLocalDateTime());
        return entity;
    }

    private IngestResultVO result(Long docId, boolean applied) {
        KnowledgeDocEntity current = docMapper.selectById(docId);
        return new IngestResultVO(docId, applied,
                current == null ? null : current.getStatus(), false);
    }

    private static void requireIdentity(Long headerUserId, IngestResultRequest request) {
        Long bodyUserId = parseLong(request.userId(), "user_id");
        if (!headerUserId.equals(bodyUserId)) {
            throw new BusinessException(ErrorCode.AUTH_FORBIDDEN, "X-User-Id 与请求体不一致。");
        }
        if (request.ingestJobId().length() > 64) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "ingest_job_id 过长。");
        }
    }

    private static String normalizeErrorCode(String raw) {
        if (raw == null || !ERROR_CODE.matcher(raw).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "error_code 非法。");
        }
        return raw;
    }

    private static String normalizeErrorMsg(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "error_msg 不能为空。");
        }
        return raw.length() <= 512 ? raw : raw.substring(0, 512);
    }

    private static Long parseLong(String raw, String field) {
        try {
            long value = Long.parseLong(raw);
            if (value <= 0) {
                throw new NumberFormatException("non-positive");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    field + " 必须为正的十进制字符串。");
        }
    }
}
