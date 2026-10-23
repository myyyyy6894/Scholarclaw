package com.scholarclaw.modules.knowledge.service;

import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.knowledge.dto.ParentChunkBatchRequest;
import com.scholarclaw.modules.knowledge.dto.ParentChunkBatchVO;
import com.scholarclaw.modules.knowledge.dto.ParentChunkVO;
import com.scholarclaw.modules.knowledge.mapper.AuthoritativeParentChunkRow;
import com.scholarclaw.modules.knowledge.mapper.KnowledgeParentChunkMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * `API-INT-JAVA-002`：批量权威回查父块全文（TASK-602，`04` §4.9.6 / `02` §5.2.2）。
 *
 * <p>父块内容与页码只来自 MySQL 三表关联查询；ChromaDB 元数据不能替代文档状态与版本。
 * 失效、越权或版本不匹配的候选会被 SQL 直接过滤，返回结果按请求顺序排列，允许部分命中。
 */
@Service
public class ParentChunkService {

    private static final Logger log = LoggerFactory.getLogger(ParentChunkService.class);
    private static final int MAX_PARENT_CHUNKS = 4;

    private final KnowledgeParentChunkMapper parentChunkMapper;

    public ParentChunkService(KnowledgeParentChunkMapper parentChunkMapper) {
        this.parentChunkMapper = parentChunkMapper;
    }

    public ParentChunkBatchVO batchGet(Long headerUserId, ParentChunkBatchRequest request) {
        Long bodyUserId = parsePositive(request.userId(), "user_id");
        if (!headerUserId.equals(bodyUserId)) {
            throw new BusinessException(ErrorCode.AUTH_FORBIDDEN, "X-User-Id 与请求体不一致。");
        }
        Long courseId = parsePositive(request.courseId(), "course_id");
        Integer version = request.expectedCourseIndexVersion();
        if (version == null || version < 1) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "expected_course_index_version 必须 >= 1。");
        }
        List<String> parentChunkIds = normalizeIds(request.parentChunkIds());

        List<AuthoritativeParentChunkRow> rows = parentChunkMapper.selectAuthoritativeBatch(
                bodyUserId, courseId, version, parentChunkIds);
        Map<String, AuthoritativeParentChunkRow> byId = new LinkedHashMap<>();
        for (AuthoritativeParentChunkRow row : rows) {
            if (isValid(row)) {
                byId.put(row.getParentChunkId(), row);
            } else {
                log.warn("丢弃字段不完整的权威父块 docId={} parentChunkId={}",
                        row.getDocId(), row.getParentChunkId());
            }
        }
        List<ParentChunkVO> result = new ArrayList<>(byId.size());
        for (String parentChunkId : parentChunkIds) {
            AuthoritativeParentChunkRow row = byId.get(parentChunkId);
            if (row != null) {
                result.add(toVO(row));
            }
        }
        log.debug("父块权威回查 userId={} courseId={} version={} requested={} valid={}",
                bodyUserId, courseId, version, parentChunkIds.size(), result.size());
        return new ParentChunkBatchVO(result);
    }

    private static List<String> normalizeIds(List<String> rawIds) {
        if (rawIds == null || rawIds.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "parent_chunk_ids 长度必须为 1~4。");
        }
        if (rawIds.size() > MAX_PARENT_CHUNKS) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "parent_chunk_ids 最多允许 4 个。");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String rawId : rawIds) {
            String id = rawId == null ? "" : rawId.trim();
            if (!id.matches("[A-Za-z0-9_]{1,64}")) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "parent_chunk_id 格式非法。");
            }
            normalized.add(id);
        }
        return List.copyOf(normalized);
    }

    private static boolean isValid(AuthoritativeParentChunkRow row) {
        return row.getParentChunkId() != null
                && row.getDocId() != null
                && row.getDocName() != null && !row.getDocName().isBlank()
                && row.getPageStart() != null && row.getPageEnd() != null
                && row.getPageStart() >= 1 && row.getPageEnd() >= row.getPageStart()
                && row.getContent() != null && !row.getContent().isBlank();
    }

    private static ParentChunkVO toVO(AuthoritativeParentChunkRow row) {
        return new ParentChunkVO(row.getParentChunkId(), row.getDocId(), row.getDocName(),
                row.getPageStart(), row.getPageEnd(), row.getSectionTitle(), row.getContent());
    }

    private static Long parsePositive(String raw, String field) {
        if (raw == null || !raw.matches("\\d{1,19}")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    field + " 必须为正的十进制字符串。");
        }
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
