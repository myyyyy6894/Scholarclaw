package com.scholarclaw.modules.channel.service;

import com.scholarclaw.common.client.OneBotApiClient;
import com.scholarclaw.common.client.OneBotApiClient.DownloadedFile;
import com.scholarclaw.common.config.ScTimeZone;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.agent.dto.AgentInboundMessage;
import com.scholarclaw.modules.agent.dto.AgentReply;
import com.scholarclaw.modules.agent.service.AgentOrchestratorService;
import com.scholarclaw.modules.channel.dto.OneBotEvent;
import com.scholarclaw.modules.knowledge.dto.KnowledgeDocVO;
import com.scholarclaw.modules.knowledge.service.KnowledgeUploadService;
import com.scholarclaw.modules.knowledge.service.KnowledgeUploadService.DefaultCourseUpload;
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import com.scholarclaw.modules.userconfig.service.ModelConfigRules;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * OneBot 入站异步业务适配器（TASK-803）。
 *
 * <p>同步网关已完成验签、白名单、去重与 ACK；本类只处理已授权的单条事件：
 * <ul>
 *   <li>文本/单层合并转发 → {@link AgentOrchestratorService}，把 {@code replyText} 私聊回复；</li>
 *   <li>文件 → 先按 DB-07 校验 Embedding 配置，再下载、默认课程入库并回执；</li>
 *   <li>心跳/未 @ 群消息/非群文件通知 → 直接忽略。</li>
 * </ul>
 *
 * <p><b>不产生业务状态之外的副作用：</b>发送回复失败只影响 QQ 回执，站内通知与文档/DDL 状态
 * 已由各业务事务保证；下一次重投由调用方删除幂等键触发，下游幂等键/文件 SHA 查重会收敛重复。
 */
@Service
public class OneBotChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(OneBotChannelAdapter.class);
    private static final int MAX_TEXT_LENGTH = 4000;
    private static final String GENERIC_FAILURE =
            "⚠️ 消息处理失败，请稍后在 PC Web 工作台重试。";

    private final AgentOrchestratorService agentOrchestratorService;
    private final KnowledgeUploadService knowledgeUploadService;
    private final UserConfigService userConfigService;
    private final ModelConfigRules modelConfigRules;
    private final OneBotApiClient oneBotApiClient;

    public OneBotChannelAdapter(AgentOrchestratorService agentOrchestratorService,
                                KnowledgeUploadService knowledgeUploadService,
                                UserConfigService userConfigService,
                                ModelConfigRules modelConfigRules,
                                OneBotApiClient oneBotApiClient) {
        this.agentOrchestratorService = agentOrchestratorService;
        this.knowledgeUploadService = knowledgeUploadService;
        this.userConfigService = userConfigService;
        this.modelConfigRules = modelConfigRules;
        this.oneBotApiClient = oneBotApiClient;
    }

    /** 处理一条已通过鉴权/白名单/去重的入站事件。 */
    public void process(OneBotEvent event, Long userId) {
        if (event == null || event.metaEvent() || event.ignored()) {
            return;
        }
        Long imUid = parseImUid(event.senderId());
        if (imUid == null) {
            log.warn("OneBot 事件缺少合法 sender_id，忽略 messageId={}", event.messageId());
            return;
        }
        if (event.hasFile()) {
            processFile(event, userId, imUid);
            return;
        }
        if (event.hasForward()) {
            String forwarded;
            try {
                forwarded = oneBotApiClient.getForwardText(event.forwardId());
            } catch (BusinessException e) {
                reply(imUid, userMessage(e));
                return;
            }
            processText(event, userId, imUid, forwarded);
            return;
        }
        if (event.hasText()) {
            processText(event, userId, imUid, event.text());
        }
    }

    private void processText(OneBotEvent event, Long userId, Long imUid, String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return;
        }
        if (rawText.length() > MAX_TEXT_LENGTH) {
            reply(imUid, "⚠️ 消息文本过长，请分段发送，或在 PC Web 工作台粘贴完整通知。");
            return;
        }
        try {
            AgentReply result = agentOrchestratorService.handle(new AgentInboundMessage(
                    userId,
                    "QQ_ONEBOT",
                    event.messageId(),
                    rawText,
                    referenceTime(event.eventTime(), userId),
                    null,
                    false));
            if (result != null && result.replyText() != null && !result.replyText().isBlank()) {
                reply(imUid, result.replyText());
            }
        } catch (BusinessException e) {
            log.warn("OneBot 文本处理失败 messageId={} code={}",
                    event.messageId(), e.getErrorCode().code());
            reply(imUid, userMessage(e));
        } catch (RuntimeException e) {
            log.error("OneBot 文本处理未预期失败 messageId={} error={}",
                    event.messageId(), e.getClass().getSimpleName(), e);
            reply(imUid, GENERIC_FAILURE);
        }
    }

    private void processFile(OneBotEvent event, Long userId, Long imUid) {
        Optional<UserConfigEntity> configOpt = userConfigService.findById(userId);
        UserConfigEntity config = configOpt.orElse(null);
        if (config == null || !modelConfigRules.isEmbeddingConfigValid(config)) {
            // DB-07：配置不完整时不得下载文件、建默认课程、写库或发 MQ。
            reply(imUid, ErrorCode.E_SYS_01.defaultMessage());
            return;
        }
        String fileName = event.fileName();
        if (!supportedExtension(fileName)) {
            reply(imUid, ErrorCode.FILE_TYPE_UNSUPPORTED.defaultMessage());
            return;
        }
        if (event.fileSize() > OneBotApiClient.MAX_FILE_BYTES) {
            reply(imUid, ErrorCode.FILE_SIZE_EXCEEDED.defaultMessage());
            return;
        }

        DownloadedFile file;
        try {
            file = oneBotApiClient.fetchFile(event.fileReference(), fileName);
        } catch (BusinessException e) {
            log.warn("OneBot 文件下载失败 messageId={} code={}",
                    event.messageId(), e.getErrorCode().code());
            reply(imUid, userMessage(e));
            return;
        }

        try {
            DefaultCourseUpload uploaded = knowledgeUploadService.uploadToDefaultCourse(
                    userId, file.fileName(), file.contentType(), file.content());
            KnowledgeDocVO doc = uploaded.doc();
            reply(imUid, "⏳ 收到文件《" + doc.docName() + "》（" + formatSize(doc.fileSizeBytes())
                    + "），已加入【" + uploaded.courseName() + "】知识库并开始解析"
                    + "（追踪号：" + com.scholarclaw.common.filter.TraceContext
                    .currentOrGeneratedTraceId() + "）。解析完成后会发送站内通知。");
        } catch (BusinessException e) {
            log.warn("OneBot 文件入库失败 messageId={} code={}",
                    event.messageId(), e.getErrorCode().code());
            reply(imUid, userMessage(e));
        } catch (RuntimeException e) {
            log.error("OneBot 文件入库未预期失败 messageId={} error={}",
                    event.messageId(), e.getClass().getSimpleName(), e);
            reply(imUid, GENERIC_FAILURE);
        }
    }

    private void reply(Long imUid, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        oneBotApiClient.sendPrivateMsg(imUid, text);
    }

    private String referenceTime(long epochSecond, Long userId) {
        ZoneId zone = userConfigService.findById(userId)
                .map(UserConfigEntity::getTimezone)
                .map(OneBotChannelAdapter::safeZone)
                .orElseGet(ScTimeZone::zoneId);
        if (epochSecond <= 0) {
            return ScTimeZone.now().toString();
        }
        return Instant.ofEpochSecond(epochSecond).atZone(zone).toOffsetDateTime().toString();
    }

    private static ZoneId safeZone(String raw) {
        try {
            return raw == null || raw.isBlank() ? ScTimeZone.zoneId() : ZoneId.of(raw.trim());
        } catch (DateTimeException e) {
            return ScTimeZone.zoneId();
        }
    }

    private static Long parseImUid(String raw) {
        if (raw == null || !raw.matches("\\d{1,19}")) {
            return null;
        }
        long value = Long.parseLong(raw);
        return value > 0 ? value : null;
    }

    private static boolean supportedExtension(String fileName) {
        if (fileName == null) {
            return false;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".pdf") || lower.endsWith(".md") || lower.endsWith(".txt");
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static String userMessage(BusinessException e) {
        ErrorCode code = e.getErrorCode();
        return switch (code) {
            case E_SYS_01, E_SYS_03, E_IM_02, E_IM_03, E_IM_04,
                 FILE_SIZE_EXCEEDED, FILE_TYPE_UNSUPPORTED, FILE_MAGIC_INVALID,
                 DOC_DUPLICATE_SHA256, UPSTREAM_MODEL_AUTH_ERROR,
                 UPSTREAM_MODEL_RATE_LIMITED, UPSTREAM_MODEL_ERROR, UPSTREAM_TIMEOUT ->
                    e.getMessage() == null || e.getMessage().isBlank()
                            ? code.defaultMessage() : e.getMessage();
            default -> GENERIC_FAILURE;
        };
    }
}
