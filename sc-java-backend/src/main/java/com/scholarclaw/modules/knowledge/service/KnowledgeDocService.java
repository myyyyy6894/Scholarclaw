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
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import com.scholarclaw.modules.userconfig.service.ModelConfigRules;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
            DocumentStatus.READY.name(), DocumentStatus.FAILED.name(),
            DocumentStatus.DELETING.name(), DocumentStatus.DELETED.name());

    /**
     * 进入未就绪状态时必须清空旧索引快照。使用显式 SQL 而不是 `.set(field, null)`：
     * MyBatis-Plus 实体更新策略默认忽略 null，只有 `setSql` 能保证数据库值真的被清空。
     */
    public static final String CLEAR_INDEX_SNAPSHOT_SQL =
            "indexed_embedding_provider = NULL, indexed_embedding_model = NULL, "
                    + "indexed_embedding_dim = NULL, page_count = 0, chunk_count = 0, ready_at = NULL";

    private final KnowledgeDocMapper docMapper;
    private final KnowledgeParentChunkMapper parentChunkMapper;
    private final CourseService courseService;
    private final UserConfigService userConfigService;
    private final ModelConfigRules modelConfigRules;

    public KnowledgeDocService(KnowledgeDocMapper docMapper,
                               KnowledgeParentChunkMapper parentChunkMapper,
                               CourseService courseService,
                               UserConfigService userConfigService,
                               ModelConfigRules modelConfigRules) {
        this.docMapper = docMapper;
        this.parentChunkMapper = parentChunkMapper;
        this.courseService = courseService;
        this.userConfigService = userConfigService;
        this.modelConfigRules = modelConfigRules;
    }

    /**
     * `API-DOC-001`：查询课程下文档；默认返回 `status != 'DELETED'`（含 `DELETING` 过渡态），
     * 可选按六态过滤；`DELETED` 只能通过显式 `status=DELETED` 查询。
     */
    public List<KnowledgeDocVO> listDocs(Long userId, Long courseId, String status) {
        courseService.getActiveCourse(userId, courseId);
        var query = Wrappers.<KnowledgeDocEntity>lambdaQuery()
                .eq(KnowledgeDocEntity::getUserId, userId)
                .eq(KnowledgeDocEntity::getCourseId, courseId);
        if (status == null || status.isBlank()) {
            query.ne(KnowledgeDocEntity::getStatus, DocumentStatus.DELETED.name());
        } else {
            String normalized = status.trim().toUpperCase(Locale.ROOT);
            if (!LISTABLE_STATUSES.contains(normalized)) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "status 仅允许 UPLOADED/PARSING/READY/FAILED/DELETING/DELETED。");
            }
            query.eq(KnowledgeDocEntity::getStatus, normalized);
        }
        query.orderByDesc(KnowledgeDocEntity::getCreatedAt);
        ZoneId zone = ScTimeZone.zoneId();
        return docMapper.selectList(query).stream().map(doc -> toVO(doc, zone)).toList();
    }

    /**
     * 插入文档元数据（上传事务内调用）。
     *
     * @param course 已由调用方 `SELECT ... FOR UPDATE` 锁定的活动课程行
     */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeDocEntity createDocumentMetadata(CourseNamespaceEntity course, String docName,
                                                     String fileExt, long fileSizeBytes,
                                                     String fileSha256, String storageRelPath) {
        if (IndexStatus.REBUILDING.name().equals(course.getIndexStatus())) {
            throw new BusinessException(ErrorCode.E_SYS_03,
                    "当前课程索引正在重建中，请等待完成后再上传。");
        }
        LocalDateTime now = ScTimeZone.now().toLocalDateTime();
        KnowledgeDocEntity entity = new KnowledgeDocEntity();
        entity.setUserId(course.getUserId());
        entity.setCourseId(course.getCourseId());
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
        docMapper.insert(entity);
        return entity;
    }

    /** 同课程活动文档按 SHA-256 预检；返回已有文档用于 409 结构化详情。 */
    public KnowledgeDocEntity findActiveShaDoc(Long userId, Long courseId, String fileSha256) {
        return docMapper.selectOne(Wrappers.<KnowledgeDocEntity>lambdaQuery()
                .eq(KnowledgeDocEntity::getUserId, userId)
                .eq(KnowledgeDocEntity::getCourseId, courseId)
                .eq(KnowledgeDocEntity::getFileSha256, fileSha256)
                .notIn(KnowledgeDocEntity::getStatus,
                        DocumentStatus.DELETING.name(), DocumentStatus.DELETED.name())
                .last("LIMIT 1"));
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
        return markParsing(docId, parseVersion, null);
    }

    /** `UPLOADED -> PARSING`，同时回填任务 ID 与入队时间。 */
    @Transactional(rollbackFor = Exception.class)
    public boolean markParsing(Long docId, int parseVersion, String ingestJobId) {
        LocalDateTime now = ScTimeZone.now().toLocalDateTime();
        var update = Wrappers.<KnowledgeDocEntity>lambdaUpdate()
                .eq(KnowledgeDocEntity::getDocId, docId)
                .eq(KnowledgeDocEntity::getParseVersion, parseVersion)
                .eq(KnowledgeDocEntity::getStatus, DocumentStatus.UPLOADED.name())
                .set(KnowledgeDocEntity::getStatus, DocumentStatus.PARSING.name())
                .set(KnowledgeDocEntity::getEnqueuedAt, now)
                .set(KnowledgeDocEntity::getUpdatedAt, now)
                .setSql(CLEAR_INDEX_SNAPSHOT_SQL);
        if (ingestJobId != null) {
            update.set(KnowledgeDocEntity::getLastIngestJobId, ingestJobId);
        } else {
            update.setSql("last_ingest_job_id = NULL");
        }
        return docMapper.update(null, update) == 1;
    }

    /** 回填 `PARSING` 文档的任务 ID 与入队时间（重试/重建/课程切换后的补投使用）。 */
    @Transactional(rollbackFor = Exception.class)
    public boolean recordEnqueued(Long docId, int parseVersion, String ingestJobId) {
        LocalDateTime now = ScTimeZone.now().toLocalDateTime();
        return docMapper.update(null, Wrappers.<KnowledgeDocEntity>lambdaUpdate()
                .eq(KnowledgeDocEntity::getDocId, docId)
                .eq(KnowledgeDocEntity::getParseVersion, parseVersion)
                .eq(KnowledgeDocEntity::getStatus, DocumentStatus.PARSING.name())
                .set(KnowledgeDocEntity::getLastIngestJobId, ingestJobId)
                .set(KnowledgeDocEntity::getEnqueuedAt, now)
                .set(KnowledgeDocEntity::getUpdatedAt, now)) == 1;
    }

    /** `UPLOADED` 且尚未成功入队的僵尸文档（`ZombieIngestScanner` 补投）。 */
    public List<KnowledgeDocEntity> findStaleUploaded(LocalDateTime updatedBefore, int limit) {
        return docMapper.selectList(Wrappers.<KnowledgeDocEntity>lambdaQuery()
                .eq(KnowledgeDocEntity::getStatus, DocumentStatus.UPLOADED.name())
                .ne(KnowledgeDocEntity::getStorageRelPath, "pending")
                .isNull(KnowledgeDocEntity::getEnqueuedAt)
                .lt(KnowledgeDocEntity::getUpdatedAt, updatedBefore)
                .orderByAsc(KnowledgeDocEntity::getUpdatedAt)
                .last("LIMIT " + Math.max(1, Math.min(limit, 200))));
    }

    /** 课程切换后需要发布 `REINDEX` 的 `PARSING` 文档。 */
    public List<KnowledgeDocEntity> findParsingForCourse(Long userId, Long courseId,
                                                         int courseIndexVersion) {
        return docMapper.selectList(Wrappers.<KnowledgeDocEntity>lambdaQuery()
                .eq(KnowledgeDocEntity::getUserId, userId)
                .eq(KnowledgeDocEntity::getCourseId, courseId)
                .eq(KnowledgeDocEntity::getCourseIndexVersion, courseIndexVersion)
                .eq(KnowledgeDocEntity::getStatus, DocumentStatus.PARSING.name())
                .orderByAsc(KnowledgeDocEntity::getDocId));
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
                .set(KnowledgeDocEntity::getUpdatedAt, ScTimeZone.now().toLocalDateTime())
                .setSql(CLEAR_INDEX_SNAPSHOT_SQL)) == 1;
    }

    /** `FAILED -> PARSING`：`API-DOC-003`；发布 `RETRY_FAILED` 由调用方在事务提交后执行。 */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeDocEntity retryFailed(Long userId, Long docId) {
        return retryFailed(userId, docId, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public KnowledgeDocEntity retryFailed(Long userId, Long docId, String ingestJobId) {
        requireEmbeddingConfig(userId);
        return transitionToParsing(userId, docId, DocumentStatus.FAILED,
                "仅解析失败(FAILED)状态的课件可执行手动重试。", ingestJobId);
    }

    /** `READY -> PARSING`：`API-DOC-004`。 */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeDocEntity reindexReady(Long userId, Long docId) {
        return reindexReady(userId, docId, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public KnowledgeDocEntity reindexReady(Long userId, Long docId, String ingestJobId) {
        requireEmbeddingConfig(userId);
        return transitionToParsing(userId, docId, DocumentStatus.READY,
                "文档当前非就绪状态，请勿重复操作。", ingestJobId);
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
                .setSql("parse_version = parse_version + 1, " + CLEAR_INDEX_SNAPSHOT_SQL)
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

    /** 上传补偿：仅删除仍是 UPLOADED 的未完成元数据，绝不误删已进入解析的文档。 */
    @Transactional(rollbackFor = Exception.class)
    public void deleteUncommittedMetadata(Long userId, Long docId) {
        docMapper.delete(Wrappers.<KnowledgeDocEntity>lambdaQuery()
                .eq(KnowledgeDocEntity::getDocId, docId)
                .eq(KnowledgeDocEntity::getUserId, userId)
                .eq(KnowledgeDocEntity::getStatus, DocumentStatus.UPLOADED.name()));
    }

    /** 对外视图转换（上传接口返回新文档时复用同一字段集）。 */
    public KnowledgeDocVO toVO(KnowledgeDocEntity doc) {
        return toVO(doc, ScTimeZone.zoneId());
    }

    private KnowledgeDocEntity transitionToParsing(Long userId, Long docId,
                                                   DocumentStatus requiredStatus, String message,
                                                   String ingestJobId) {
        KnowledgeDocEntity doc = requireOwnedDoc(userId, docId);
        if (!requiredStatus.name().equals(doc.getStatus())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, message);
        }
        CourseNamespaceEntity course = courseService.getActiveCourse(userId, doc.getCourseId());
        var update = Wrappers.<KnowledgeDocEntity>lambdaUpdate()
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
                .setSql("parse_version = parse_version + 1, " + CLEAR_INDEX_SNAPSHOT_SQL);
        if (ingestJobId == null) {
            update.setSql("last_ingest_job_id = NULL");
        } else {
            update.set(KnowledgeDocEntity::getLastIngestJobId, ingestJobId);
        }
        int affected = docMapper.update(null, update);
        if (affected != 1) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, message);
        }
        return requireOwnedDoc(userId, docId);
    }

    private void requireEmbeddingConfig(Long userId) {
        UserConfigEntity config = userConfigService.findById(userId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.AUTH_UNAUTHORIZED, "登录状态已失效，请重新登录。"));
        if (!modelConfigRules.isEmbeddingConfigValid(config)) {
            throw new BusinessException(ErrorCode.E_SYS_01,
                    "⚠️ 尚未配置完整的向量模型（Embedding），请先前往【个人设置 -> 模型配置】完成配置。");
        }
    }

    private static KnowledgeDocVO toVO(KnowledgeDocEntity doc, ZoneId zone) {
        // 只有 READY 才允许暴露索引快照；历史/异常中断数据即使残留旧值也必须对外隐藏
        boolean ready = DocumentStatus.READY.name().equals(doc.getStatus());
        return new KnowledgeDocVO(
                doc.getDocId(),
                doc.getCourseId(),
                doc.getDocName(),
                doc.getFileExt(),
                doc.getFileSizeBytes() == null ? 0 : doc.getFileSizeBytes(),
                doc.getStatus(),
                doc.getParseVersion() == null ? 1 : doc.getParseVersion(),
                doc.getCourseIndexVersion() == null ? 1 : doc.getCourseIndexVersion(),
                ready ? doc.getIndexedEmbeddingModel() : null,
                ready ? doc.getIndexedEmbeddingDim() : null,
                ready && doc.getPageCount() != null ? doc.getPageCount() : 0,
                ready && doc.getChunkCount() != null ? doc.getChunkCount() : 0,
                doc.getRetryCount() == null ? 0 : doc.getRetryCount(),
                doc.getLastIngestJobId(),
                doc.getErrorCode(),
                doc.getErrorMsg(),
                formatTimestamp(doc.getEnqueuedAt(), zone),
                ready ? formatTimestamp(doc.getReadyAt(), zone) : null,
                formatTimestamp(doc.getCreatedAt(), zone),
                formatTimestamp(doc.getUpdatedAt(), zone));
    }

    private static String formatTimestamp(LocalDateTime value, ZoneId zone) {
        return value == null ? null : value.atZone(zone).toOffsetDateTime().format(TIMESTAMP_FORMAT);
    }
}
