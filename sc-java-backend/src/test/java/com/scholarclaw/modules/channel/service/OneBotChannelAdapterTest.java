package com.scholarclaw.modules.channel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scholarclaw.common.client.OneBotApiClient;
import com.scholarclaw.common.client.OneBotApiClient.DownloadedFile;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.agent.dto.AgentIntent;
import com.scholarclaw.modules.agent.dto.AgentReply;
import com.scholarclaw.modules.agent.dto.AgentToolAction;
import com.scholarclaw.modules.agent.service.AgentOrchestratorService;
import com.scholarclaw.modules.channel.dto.OneBotEvent;
import com.scholarclaw.modules.knowledge.dto.KnowledgeDocVO;
import com.scholarclaw.modules.knowledge.service.KnowledgeUploadService;
import com.scholarclaw.modules.knowledge.service.KnowledgeUploadService.DefaultCourseUpload;
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import com.scholarclaw.modules.userconfig.service.ModelConfigRules;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** TASK-803 入站异步业务：文本/转发编排、文件 DB-07 前置拦截、下载与入库回执。 */
class OneBotChannelAdapterTest {

    private static final long USER_ID = 10001L;
    private static final long IM_UID = 24681012L;
    private static final String MESSAGE_ID = "188992211";

    private AgentOrchestratorService agent;
    private KnowledgeUploadService uploadService;
    private UserConfigService userConfigService;
    private ModelConfigRules modelConfigRules;
    private OneBotApiClient oneBotApiClient;
    private OneBotChannelAdapter adapter;

    @BeforeEach
    void setUp() {
        agent = mock(AgentOrchestratorService.class);
        uploadService = mock(KnowledgeUploadService.class);
        userConfigService = mock(UserConfigService.class);
        modelConfigRules = mock(ModelConfigRules.class);
        oneBotApiClient = mock(OneBotApiClient.class);
        adapter = new OneBotChannelAdapter(agent, uploadService,
                userConfigService, modelConfigRules, oneBotApiClient);
        when(userConfigService.findById(USER_ID)).thenReturn(Optional.of(user()));
    }

    @Test
    @DisplayName("私聊文本：传给编排层并私聊回复 replyText")
    void routesTextToAgentAndReplies() {
        when(agent.handle(any())).thenReturn(reply("已创建任务 #T102"));

        adapter.process(textEvent("数据库作业 10月25日交"), USER_ID);

        ArgumentCaptor<com.scholarclaw.modules.agent.dto.AgentInboundMessage> inbound =
                ArgumentCaptor.forClass(com.scholarclaw.modules.agent.dto.AgentInboundMessage.class);
        verify(agent).handle(inbound.capture());
        assertThat(inbound.getValue().userId()).isEqualTo(USER_ID);
        assertThat(inbound.getValue().sourcePlatform()).isEqualTo("QQ_ONEBOT");
        assertThat(inbound.getValue().sourceMsgId()).isEqualTo(MESSAGE_ID);
        assertThat(inbound.getValue().rawText()).isEqualTo("数据库作业 10月25日交");
        assertThat(inbound.getValue().referenceTime()).contains("+08:00");
        verify(oneBotApiClient).sendPrivateMsg(IM_UID, "已创建任务 #T102");
    }

    @Test
    @DisplayName("超长文本：直接引导 Web，不调用模型")
    void rejectsOverlongText() {
        adapter.process(textEvent("长".repeat(4001)), USER_ID);

        verify(agent, never()).handle(any());
        verify(oneBotApiClient).sendPrivateMsg(eq(IM_UID), anyString());
    }

    @Test
    @DisplayName("编排层业务异常：回复脱敏文案")
    void repliesBusinessFailure() {
        when(agent.handle(any())).thenThrow(new BusinessException(ErrorCode.E_SYS_01));

        adapter.process(textEvent("hi"), USER_ID);

        verify(oneBotApiClient).sendPrivateMsg(IM_UID, ErrorCode.E_SYS_01.defaultMessage());
    }

    @Test
    @DisplayName("编排层未预期异常：通用失败文案，不泄露堆栈")
    void repliesGenericFailure() {
        when(agent.handle(any())).thenThrow(new IllegalStateException("db secret"));

        adapter.process(textEvent("hi"), USER_ID);

        verify(oneBotApiClient).sendPrivateMsg(IM_UID,
                "⚠️ 消息处理失败，请稍后在 PC Web 工作台重试。");
    }

    @Test
    @DisplayName("单层合并转发：拉取文本后交给编排层")
    void routesForwardedTextToAgent() {
        when(oneBotApiClient.getForwardText("forward-1")).thenReturn("第一条\n第二条");
        when(agent.handle(any())).thenReturn(reply("已处理"));

        adapter.process(forwardEvent(), USER_ID);

        verify(agent).handle(any());
        verify(oneBotApiClient).sendPrivateMsg(IM_UID, "已处理");
    }

    @Test
    @DisplayName("合并转发读取失败：回复 E-IM-03 引导，不调用编排")
    void repliesForwardFailure() {
        when(oneBotApiClient.getForwardText("forward-1"))
                .thenThrow(new BusinessException(ErrorCode.E_IM_03));

        adapter.process(forwardEvent(), USER_ID);

        verify(agent, never()).handle(any());
        verify(oneBotApiClient).sendPrivateMsg(IM_UID, ErrorCode.E_IM_03.defaultMessage());
    }

    @Test
    @DisplayName("Embedding 未配置（DB-07）：不下载、不建课程、不写库、不发 MQ，只回复引导")
    void blocksFileBeforeDownloadWhenEmbeddingMissing() {
        when(modelConfigRules.isEmbeddingConfigValid(any())).thenReturn(false);

        adapter.process(fileEvent("a.pdf"), USER_ID);

        verify(oneBotApiClient, never()).fetchFile(anyString(), anyString());
        verify(uploadService, never()).uploadBytes(any(), any(), any(), any(), any());
        verify(uploadService, never()).uploadToDefaultCourse(anyLong(), anyString(),
                anyString(), any());
        verify(oneBotApiClient).sendPrivateMsg(IM_UID, ErrorCode.E_SYS_01.defaultMessage());
    }

    @Test
    @DisplayName("不支持的文件类型：下载前拒绝")
    void rejectsUnsupportedFileType() {
        when(modelConfigRules.isEmbeddingConfigValid(any())).thenReturn(true);

        adapter.process(fileEvent("virus.exe"), USER_ID);

        verify(oneBotApiClient, never()).fetchFile(anyString(), anyString());
        verify(oneBotApiClient).sendPrivateMsg(IM_UID,
                ErrorCode.FILE_TYPE_UNSUPPORTED.defaultMessage());
    }

    @Test
    @DisplayName("合法 PDF：下载后进入默认课程知识库并回执")
    void ingestsPdfIntoDefaultCourse() {
        when(modelConfigRules.isEmbeddingConfigValid(any())).thenReturn(true);
        when(oneBotApiClient.fetchFile("file-1", "a.pdf"))
                .thenReturn(new DownloadedFile("a.pdf", "application/pdf",
                        "%PDF-1.7".getBytes(StandardCharsets.UTF_8)));
        when(uploadService.uploadToDefaultCourse(eq(USER_ID), eq("a.pdf"),
                eq("application/pdf"), any()))
                .thenReturn(new DefaultCourseUpload(doc(), "未分类课程"));

        adapter.process(fileEvent("a.pdf"), USER_ID);

        verify(uploadService).uploadToDefaultCourse(eq(USER_ID), eq("a.pdf"),
                eq("application/pdf"), any());
        ArgumentCaptor<String> reply = ArgumentCaptor.forClass(String.class);
        verify(oneBotApiClient).sendPrivateMsg(eq(IM_UID), reply.capture());
        assertThat(reply.getValue()).contains("a.pdf").contains("未分类课程")
                .contains("追踪号");
    }

    @Test
    @DisplayName("文件下载失败：回复 E-IM-02 引导 Web 上传")
    void repliesDownloadFailure() {
        when(modelConfigRules.isEmbeddingConfigValid(any())).thenReturn(true);
        when(oneBotApiClient.fetchFile(anyString(), anyString()))
                .thenThrow(new BusinessException(ErrorCode.E_IM_02));

        adapter.process(fileEvent("a.pdf"), USER_ID);

        verify(uploadService, never()).uploadToDefaultCourse(anyLong(), anyString(),
                anyString(), any());
        verify(oneBotApiClient).sendPrivateMsg(IM_UID, ErrorCode.E_IM_02.defaultMessage());
    }

    @Test
    @DisplayName("重复文件：透出业务异常文案，不重复入库")
    void repliesDuplicateFile() {
        when(modelConfigRules.isEmbeddingConfigValid(any())).thenReturn(true);
        when(oneBotApiClient.fetchFile(anyString(), anyString()))
                .thenReturn(new DownloadedFile("a.pdf", "application/pdf", new byte[]{1}));
        when(uploadService.uploadToDefaultCourse(anyLong(), anyString(), anyString(), any()))
                .thenThrow(new BusinessException(ErrorCode.DOC_DUPLICATE_SHA256,
                        "该课件已存在于当前课程知识库中，无需重复上传。"));

        adapter.process(fileEvent("a.pdf"), USER_ID);

        verify(oneBotApiClient).sendPrivateMsg(IM_UID,
                "该课件已存在于当前课程知识库中，无需重复上传。");
    }

    @Test
    @DisplayName("群聊 @ 文本：回复必须发回原群，不能私聊发送者")
    void repliesGroupMessageToGroup() {
        when(agent.handle(any())).thenReturn(reply("已创建任务 #T102"));

        adapter.process(groupTextEvent("7654321", "数据库作业 10月25日交"), USER_ID);

        verify(oneBotApiClient).sendGroupMsg(7654321L, "已创建任务 #T102");
        verify(oneBotApiClient, never()).sendPrivateMsg(anyLong(), anyString());
    }

    @Test
    @DisplayName("事件没有文件名：先 get_file 解析文件名，再做扩展名与下载校验")
    void resolvesFileNameBeforeExtensionCheck() {
        when(modelConfigRules.isEmbeddingConfigValid(any())).thenReturn(true);
        when(oneBotApiClient.resolveFileName("file-1")).thenReturn("b.pdf");
        when(oneBotApiClient.fetchFile("file-1", "b.pdf"))
                .thenReturn(new DownloadedFile("b.pdf", "application/pdf",
                        "%PDF-1.7".getBytes(StandardCharsets.UTF_8)));
        when(uploadService.uploadToDefaultCourse(eq(USER_ID), eq("b.pdf"),
                eq("application/pdf"), any()))
                .thenReturn(new DefaultCourseUpload(doc(), "未分类课程"));

        adapter.process(fileEventWithoutName("file-1"), USER_ID);

        verify(oneBotApiClient).resolveFileName("file-1");
        verify(oneBotApiClient).fetchFile("file-1", "b.pdf");
        verify(oneBotApiClient).sendPrivateMsg(eq(IM_UID), anyString());
    }

    @Test
    @DisplayName("get_file 解析出错误扩展名：快速拒绝，不下载内容")
    void rejectsUnsupportedResolvedFileName() {
        when(modelConfigRules.isEmbeddingConfigValid(any())).thenReturn(true);
        when(oneBotApiClient.resolveFileName("file-1")).thenReturn("virus.exe");

        adapter.process(fileEventWithoutName("file-1"), USER_ID);

        verify(oneBotApiClient, never()).fetchFile(anyString(), anyString());
        verify(oneBotApiClient).sendPrivateMsg(IM_UID,
                ErrorCode.FILE_TYPE_UNSUPPORTED.defaultMessage());
    }

    @Test
    @DisplayName("DB-07 未配置时不得先调用 get_file 解析文件名")
    void blocksMetadataLookupBeforeEmbeddingCheck() {
        when(modelConfigRules.isEmbeddingConfigValid(any())).thenReturn(false);

        adapter.process(fileEventWithoutName("file-1"), USER_ID);

        verify(oneBotApiClient, never()).resolveFileName(anyString());
        verify(oneBotApiClient, never()).fetchFile(anyString(), anyString());
        verify(oneBotApiClient).sendPrivateMsg(IM_UID, ErrorCode.E_SYS_01.defaultMessage());
    }

    @Test
    @DisplayName("心跳/未 @ 群消息：不处理、不回复")
    void ignoresNonBusinessEvents() {
        adapter.process(new OneBotEvent("meta_event", null, null, null, null, null, null, 0,
                null, null, null, null, -1, true, false, true, "meta_event"), USER_ID);

        verify(agent, never()).handle(any());
        verify(oneBotApiClient, never()).sendPrivateMsg(anyLong(), anyString());
    }

    private static OneBotEvent textEvent(String text) {
        return new OneBotEvent("message", "private", null, MESSAGE_ID,
                String.valueOf(IM_UID), "987654321", null, 1760509200L, text,
                null, null, null, -1, false, false, false, null);
    }

    private static OneBotEvent forwardEvent() {
        return new OneBotEvent("message", "private", null, MESSAGE_ID,
                String.valueOf(IM_UID), "987654321", null, 1760509200L, null,
                "forward-1", null, null, -1, false, false, false, null);
    }

    private static OneBotEvent fileEvent(String fileName) {
        return new OneBotEvent("message", "private", null, MESSAGE_ID,
                String.valueOf(IM_UID), "987654321", null, 1760509200L, null,
                null, "file-1", fileName, 1024, false, false, false, null);
    }

    private static OneBotEvent groupTextEvent(String groupId, String text) {
        return new OneBotEvent("message", "group", null, MESSAGE_ID,
                String.valueOf(IM_UID), "987654321", groupId, 1760509200L, text,
                null, null, null, -1, false, true, false, null);
    }

    private static OneBotEvent fileEventWithoutName(String fileReference) {
        return new OneBotEvent("message", "private", null, MESSAGE_ID,
                String.valueOf(IM_UID), "987654321", null, 1760509200L, null,
                null, fileReference, null, 1024, false, false, false, null);
    }

    private static UserConfigEntity user() {
        UserConfigEntity user = new UserConfigEntity();
        user.setUserId(USER_ID);
        user.setAccountStatus("ACTIVE");
        user.setTimezone("Asia/Shanghai");
        return user;
    }

    private static KnowledgeDocVO doc() {
        return new KnowledgeDocVO(50001L, 2001L, "a.pdf", "pdf", 1024L, "PARSING",
                1, 1, null, null, 0, 0, 0, "job-1", null, null,
                null, null, null, null);
    }

    private static AgentReply reply(String text) {
        return new AgentReply(AgentIntent.DDL_EXTRACT, BigDecimal.ONE,
                AgentToolAction.DDL_EXTRACT, text, null, null, false);
    }
}
