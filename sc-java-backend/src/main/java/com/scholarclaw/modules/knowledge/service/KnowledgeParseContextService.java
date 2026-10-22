package com.scholarclaw.modules.knowledge.service;

import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.common.security.AesGcmCryptoService;
import com.scholarclaw.modules.course.entity.CourseNamespaceEntity;
import com.scholarclaw.modules.course.service.CourseService;
import com.scholarclaw.modules.knowledge.dto.ParseContextVO;
import com.scholarclaw.modules.knowledge.entity.KnowledgeDocEntity;
import com.scholarclaw.modules.knowledge.mapper.KnowledgeDocMapper;
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import com.scholarclaw.modules.userconfig.service.ModelConfigRules;
import com.scholarclaw.modules.userconfig.service.ModelKeyBindingRules;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * `API-INT-JAVA-001`：解析上下文核验（TASK-503）。
 *
 * <p>只有版本、状态与 Embedding 配置全部有效时才返回 `allow_proceed=true`；
 * 否则返回明确的 `abort_reason`，由 Python 直接 ack 丢弃消息。
 */
@Service
public class KnowledgeParseContextService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeParseContextService.class);
    private static final String EMBEDDING_MISSING = "EMBEDDING_CONFIG_MISSING";

    private final KnowledgeDocMapper docMapper;
    private final KnowledgeDocService docService;
    private final CourseService courseService;
    private final UserConfigService userConfigService;
    private final ModelConfigRules modelConfigRules;
    private final ModelKeyBindingRules modelKeyBindingRules;
    private final AesGcmCryptoService cryptoService;

    public KnowledgeParseContextService(KnowledgeDocMapper docMapper,
                                        KnowledgeDocService docService,
                                        CourseService courseService,
                                        UserConfigService userConfigService,
                                        ModelConfigRules modelConfigRules,
                                        ModelKeyBindingRules modelKeyBindingRules,
                                        AesGcmCryptoService cryptoService) {
        this.docMapper = docMapper;
        this.docService = docService;
        this.courseService = courseService;
        this.userConfigService = userConfigService;
        this.modelConfigRules = modelConfigRules;
        this.modelKeyBindingRules = modelKeyBindingRules;
        this.cryptoService = cryptoService;
    }

    public ParseContextVO resolve(Long headerUserId, Long docId,
                                  int parseVersion, int courseIndexVersion) {
        KnowledgeDocEntity doc = docMapper.selectById(docId);
        if (doc == null || !doc.getUserId().equals(headerUserId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        if (!doc.getParseVersion().equals(parseVersion)
                || !doc.getCourseIndexVersion().equals(courseIndexVersion)) {
            return abort(doc, "STALE_PARSE_VERSION");
        }
        if ("DELETING".equals(doc.getStatus()) || "DELETED".equals(doc.getStatus())) {
            return abort(doc, "DOC_DELETING");
        }
        if (!"UPLOADED".equals(doc.getStatus()) && !"PARSING".equals(doc.getStatus())) {
            return abort(doc, "ALREADY_TERMINATED");
        }

        CourseNamespaceEntity course = courseService.getActiveCourse(doc.getUserId(), doc.getCourseId());
        ParseContextVO.EmbeddingConfigVO embedding = resolveEmbedding(doc, course);
        if (embedding == null) {
            docService.markFailed(doc.getDocId(), parseVersion, EMBEDDING_MISSING,
                    "Embedding 配置缺失或与已保存 Key 不匹配，请重新保存模型配置后重试。");
            log.warn("解析上下文拒绝：Embedding 配置不可用 docId={} userId={}",
                    doc.getDocId(), doc.getUserId());
            return abort(doc, EMBEDDING_MISSING);
        }

        return new ParseContextVO(true, null, doc.getUserId(), doc.getCourseId(), doc.getDocId(),
                doc.getDocName(), doc.getFileExt(), doc.getStorageRelPath(),
                doc.getParseVersion(), doc.getCourseIndexVersion(), embedding);
    }

    private ParseContextVO.EmbeddingConfigVO resolveEmbedding(KnowledgeDocEntity doc,
                                                              CourseNamespaceEntity course) {
        if (course.getEmbeddingProvider() == null || course.getEmbeddingModel() == null
                || course.getEmbeddingDim() == null || course.getEmbeddingDim() <= 0) {
            return null;
        }
        UserConfigEntity config = userConfigService.findById(doc.getUserId()).orElse(null);
        if (config == null || config.getEmbeddingBaseUrl() == null
                || config.getEmbeddingBaseUrl().isBlank()) {
            return null;
        }
        String apiKey;
        if (modelConfigRules.isWhitelistedLocalOllama(
                course.getEmbeddingProvider(), config.getEmbeddingBaseUrl())) {
            apiKey = null;
        } else {
            if (config.getEmbeddingApiKeyCipher() == null) {
                return null;
            }
            try {
                modelKeyBindingRules.requireSameTarget(
                        config.getDefaultEmbeddingProvider(), config.getEmbeddingBaseUrl(),
                        config.getDefaultEmbeddingModel(), course.getEmbeddingProvider(),
                        config.getEmbeddingBaseUrl(), course.getEmbeddingModel());
                apiKey = cryptoService.decrypt(config.getEmbeddingApiKeyCipher());
            } catch (RuntimeException e) {
                return null;
            }
        }
        return new ParseContextVO.EmbeddingConfigVO(course.getEmbeddingProvider(),
                config.getEmbeddingBaseUrl(), apiKey, course.getEmbeddingModel(),
                course.getEmbeddingDim());
    }

    private static ParseContextVO abort(KnowledgeDocEntity doc, String reason) {
        return ParseContextVO.abort(doc.getUserId(), doc.getCourseId(), doc.getDocId(),
                doc.getDocName(), doc.getFileExt(), doc.getStorageRelPath(),
                doc.getParseVersion(), doc.getCourseIndexVersion(), reason);
    }
}
