package com.scholarclaw.modules.userconfig.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.client.ModelEndpoint;
import com.scholarclaw.common.client.ModelProbeType;
import com.scholarclaw.common.client.PythonAiEngineClient;
import com.scholarclaw.common.config.JacksonConfig;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.common.exception.GlobalExceptionHandler;
import com.scholarclaw.common.security.AesGcmCryptoService;
import com.scholarclaw.common.security.BaseUrlSecurityValidator;
import com.scholarclaw.common.security.ScUserPrincipal;
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import com.scholarclaw.modules.userconfig.service.CourseEmbeddingMigrationPort;
import com.scholarclaw.modules.userconfig.service.ModelConfigRules;
import com.scholarclaw.modules.userconfig.service.ModelConfigSaveService;
import com.scholarclaw.modules.userconfig.service.ModelKeyBindingRules;
import com.scholarclaw.modules.userconfig.service.UserCourseCountProvider;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/**
 * `API-USER-002~005` 的行为契约（{@code 04} §4.2）。
 *
 * <p>重点不在字段拼装，而在三件容易写错的安全/一致性行为：
 * <ul>
 *   <li>`use_saved_key=true` 时必须解密已保存 Key 去探测，而不是把 `null` 发给 Python；</li>
 *   <li>探测失败时**绝不能**落库，用户原配置保持完好；</li>
 *   <li>IM 绑定必须成对，否则 400；唯一索引冲突映射为 409。</li>
 * </ul>
 */
class UserConfigControllerTest {

    private static final AesGcmCryptoService CRYPTO =
            new AesGcmCryptoService(Base64.getEncoder().encodeToString(new byte[32]));
    private static final ObjectMapper MAPPER = JacksonConfig
            .applyTo(new org.springframework.http.converter.json.Jackson2ObjectMapperBuilder(),
                    ZoneId.of("Asia/Shanghai")).build();
    private static final long USER_ID = 10001L;

    private UserConfigService userConfigService;
    private PythonAiEngineClient pythonAiEngineClient;
    private CourseEmbeddingMigrationPort migrationPort;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        userConfigService = mock(UserConfigService.class);
        pythonAiEngineClient = mock(PythonAiEngineClient.class);
        migrationPort = mock(CourseEmbeddingMigrationPort.class);
        org.mockito.Mockito.lenient().when(userConfigService.lockForUpdate(anyLong()))
                .thenAnswer(invocation -> userConfigService.findById(invocation.getArgument(0))
                        .orElseGet(UserConfigEntity::new));
        mockMvc = buildMvc(userId -> 0);

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new ScUserPrincipal(USER_ID, "zhangsan_cs", "STUDENT", "jti",
                        Instant.now().plusSeconds(60)), null, List.of()));
    }

    private MockMvc buildMvc(UserCourseCountProvider courseCountProvider) {
        var controller = new UserConfigController(
                userConfigService,
                CRYPTO,
                new BaseUrlSecurityValidator(false, ""),
                pythonAiEngineClient,
                new ModelConfigRules(false, ""),
                new ModelKeyBindingRules(),
                courseCountProvider,
                new ModelConfigSaveService(userConfigService, migrationPort, courseCountProvider),
                org.mockito.Mockito.mock(
                        com.scholarclaw.modules.userconfig.service.UserDeactivationService.class));
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(MAPPER))
                .setValidator(new LocalValidatorFactoryBean())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static UserConfigEntity entity() {
        UserConfigEntity entity = new UserConfigEntity();
        entity.setUserId(USER_ID);
        entity.setUsername("zhangsan_cs");
        entity.setAccountStatus("ACTIVE");
        entity.setTimezone("Asia/Shanghai");
        entity.setDndStartTime(LocalTime.of(23, 30));
        entity.setDndEndTime(LocalTime.of(7, 30));
        entity.setChatProvider("DEEPSEEK");
        entity.setChatBaseUrl("https://api.deepseek.com/v1");
        entity.setChatModel("deepseek-chat");
        entity.setDefaultEmbeddingProvider("OPENAI_COMPATIBLE");
        entity.setEmbeddingBaseUrl("https://api.siliconflow.cn/v1");
        entity.setDefaultEmbeddingModel("BAAI/bge-m3");
        entity.setDefaultEmbeddingDim(1024);
        entity.setUpdatedAt(java.time.LocalDateTime.now());
        return entity;
    }

    @Test
    @DisplayName("use_saved_key=true 时用解密后的已保存 Key 探测，明文不来自请求体")
    void probeUsesSavedKeyWhenRequested() throws Exception {
        UserConfigEntity entity = entity();
        entity.setChatApiKeyCipher(CRYPTO.encrypt("sk-saved-12345678"));
        when(userConfigService.findById(USER_ID)).thenReturn(Optional.of(entity));
        when(pythonAiEngineClient.probeModel(anyLong(), eq(ModelProbeType.CHAT), any(), any()))
                .thenReturn(new PythonAiEngineClient.ProbeResult(true, false, null, 12));

        mockMvc.perform(post("/api/v1/user/model-config/probe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"probe_type":"CHAT","provider":"DEEPSEEK",
                                 "base_url":"https://api.deepseek.com/v1",
                                 "use_saved_key":true,"model_name":"deepseek-chat"}
                                """))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ModelEndpoint> captor = ArgumentCaptor.forClass(ModelEndpoint.class);
        verify(pythonAiEngineClient).probeModel(eq(USER_ID), eq(ModelProbeType.CHAT),
                captor.capture(), isNull());
        assertThat(captor.getValue().apiKey()).isEqualTo("sk-saved-12345678");
    }

    @Test
    @DisplayName("use_saved_key=true 但 Base URL/Provider/模型变化：400 且绝不调用探测")
    void probeRejectsChangedTargetBeforeDecrypting() throws Exception {
        UserConfigEntity entity = entity();
        entity.setChatApiKeyCipher(CRYPTO.encrypt("sk-saved-12345678"));
        when(userConfigService.findById(USER_ID)).thenReturn(Optional.of(entity));

        for (String body : new String[]{
                probeBody("CHAT", "DEEPSEEK", "https://evil.example.com/v1", null,
                        true, "deepseek-chat"),
                probeBody("CHAT", "OPENAI_COMPATIBLE", "https://api.deepseek.com/v1", null,
                        true, "deepseek-chat"),
                probeBody("CHAT", "DEEPSEEK", "https://api.deepseek.com/v1", null,
                        true, "deepseek-reasoner"),
        }) {
            String response = mockMvc.perform(post("/api/v1/user/model-config/probe")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(response).contains("\"code\":\"VALIDATION_ERROR\"");
        }
        verify(pythonAiEngineClient, never()).probeModel(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("保存时改了对话目标但未输入新 Key：400，绝不落库")
    void saveRejectsChangedChatTargetWithoutNewKey() throws Exception {
        UserConfigEntity entity = entity();
        entity.setChatProvider("OPENAI_COMPATIBLE");
        entity.setChatBaseUrl("https://api.deepseek.com/v1");
        entity.setChatApiKeyCipher(CRYPTO.encrypt("sk-saved-12345678"));
        when(userConfigService.findById(USER_ID)).thenReturn(Optional.of(entity));

        int status = mockMvc.perform(put("/api/v1/user/model-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saveBody("https://evil.example.com/v1", "deepseek-chat", false, null,
                                "https://api.siliconflow.cn/v1", "sk-embed-new", "BAAI/bge-m3")))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(400);
        verify(userConfigService, never()).updateByokModelConfig(anyLong(), any());
        verify(pythonAiEngineClient, never()).probeModel(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("目标完全一致时复用已保存 Key：探测使用旧 Key，落库保留原密文")
    void saveReusesSavedKeysOnlyWhenTargetUnchanged() throws Exception {
        UserConfigEntity entity = entity();
        entity.setChatProvider("OPENAI_COMPATIBLE");
        entity.setChatApiKeyCipher(CRYPTO.encrypt("sk-saved-chat"));
        entity.setEmbeddingApiKeyCipher(CRYPTO.encrypt("sk-saved-embed"));
        when(userConfigService.findById(USER_ID)).thenReturn(Optional.of(entity));
        when(pythonAiEngineClient.probeModel(anyLong(), eq(ModelProbeType.BOTH), any(), any()))
                .thenReturn(new PythonAiEngineClient.ProbeResult(true, true, 1024, 20));
        when(userConfigService.updateByokModelConfig(anyLong(), any())).thenReturn(entity);

        int status = mockMvc.perform(put("/api/v1/user/model-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saveBody("https://api.deepseek.com/v1", "deepseek-chat", false, null,
                                "https://api.siliconflow.cn/v1", null, "BAAI/bge-m3")))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(200);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<ModelEndpoint> chatProbe = ArgumentCaptor.forClass(ModelEndpoint.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<ModelEndpoint> embedProbe = ArgumentCaptor.forClass(ModelEndpoint.class);
        verify(pythonAiEngineClient).probeModel(eq(USER_ID), eq(ModelProbeType.BOTH),
                chatProbe.capture(), embedProbe.capture());
        assertThat(chatProbe.getValue().apiKey()).isEqualTo("sk-saved-chat");
        assertThat(embedProbe.getValue().apiKey()).isEqualTo("sk-saved-embed");

        ArgumentCaptor<UserConfigService.ByokModelUpdate> update =
                ArgumentCaptor.forClass(UserConfigService.ByokModelUpdate.class);
        verify(userConfigService).updateByokModelConfig(eq(USER_ID), update.capture());
        assertThat(update.getValue().chatApiKeyCipher()).isNull();
        assertThat(update.getValue().embeddingApiKeyCipher()).isNull();
    }

    @Test
    @DisplayName("复用对话 Key 时，Embedding origin/provider 变化：400；仅模型不同：允许")
    void reuseChatKeyRespectsBindingTarget() throws Exception {
        UserConfigEntity entity = entity();
        entity.setChatProvider("OPENAI_COMPATIBLE");
        entity.setChatBaseUrl("https://api.siliconflow.cn/v1");
        entity.setChatModel("chat-model");
        entity.setChatApiKeyCipher(CRYPTO.encrypt("sk-saved-reuse"));
        when(userConfigService.findById(USER_ID)).thenReturn(Optional.of(entity));

        int rejected = mockMvc.perform(put("/api/v1/user/model-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saveBody("https://api.siliconflow.cn/v1", "chat-model", true, null,
                                "https://evil.example.com/v1", null, "BAAI/bge-m3")))
                .andReturn().getResponse().getStatus();
        assertThat(rejected).isEqualTo(400);

        when(pythonAiEngineClient.probeModel(anyLong(), eq(ModelProbeType.BOTH), any(), any()))
                .thenReturn(new PythonAiEngineClient.ProbeResult(true, true, 1024, 20));
        when(userConfigService.updateByokModelConfig(anyLong(), any())).thenReturn(entity);
        int allowed = mockMvc.perform(put("/api/v1/user/model-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saveBody("https://api.siliconflow.cn/v1", "chat-model", true, null,
                                "https://api.siliconflow.cn/v1", null, "new-embed-model")))
                .andReturn().getResponse().getStatus();
        assertThat(allowed).isEqualTo(200);
    }

    @Test
    @DisplayName("探测与保存的 Provider 集合一致：EMBEDDING 拒绝 DEEPSEEK，CHAT 允许")
    void probeProviderSetMatchesSaveRules() throws Exception {
        when(userConfigService.findById(USER_ID)).thenReturn(Optional.of(entity()));

        String rejected = mockMvc.perform(post("/api/v1/user/model-config/probe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(probeBody("EMBEDDING", "DEEPSEEK", "https://api.deepseek.com/v1",
                                "sk-x", false, "deepseek-chat")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(rejected).contains("\"code\":\"VALIDATION_ERROR\"");

        when(pythonAiEngineClient.probeModel(anyLong(), eq(ModelProbeType.CHAT), any(), any()))
                .thenReturn(new PythonAiEngineClient.ProbeResult(true, false, null, 10));
        int chatStatus = mockMvc.perform(post("/api/v1/user/model-config/probe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(probeBody("CHAT", "DEEPSEEK", "https://api.deepseek.com/v1",
                                "sk-x", false, "deepseek-chat")))
                .andReturn().getResponse().getStatus();
        assertThat(chatStatus).isEqualTo(200);
    }

    @Test
    @DisplayName("API Key 超过 512 字符：400，不进入探测/加密/写库")
    void overlongApiKeyIsRejectedByValidation() throws Exception {
        when(userConfigService.findById(USER_ID)).thenReturn(Optional.of(entity()));
        String longKey = "k".repeat(513);

        String probeResponse = mockMvc.perform(post("/api/v1/user/model-config/probe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(probeBody("CHAT", "DEEPSEEK", "https://api.deepseek.com/v1",
                                longKey, false, "deepseek-chat")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(probeResponse).contains("\"code\":\"VALIDATION_ERROR\"");

        int saveStatus = mockMvc.perform(put("/api/v1/user/model-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saveBody("https://api.deepseek.com/v1", "deepseek-chat", false,
                                longKey, "https://api.siliconflow.cn/v1", "sk-embed", "BAAI/bge-m3")))
                .andReturn().getResponse().getStatus();
        assertThat(saveStatus).isEqualTo(400);
        verify(pythonAiEngineClient, never()).probeModel(anyLong(), any(), any(), any());
        verify(userConfigService, never()).updateByokModelConfig(anyLong(), any());
    }

    @Test
    @DisplayName("非 ASCII Key：400，不进入探测（避免 HTTP 头编码异常）")
    void nonAsciiApiKeyIsRejectedByValidation() throws Exception {
        when(userConfigService.findById(USER_ID)).thenReturn(Optional.of(entity()));

        String response = mockMvc.perform(post("/api/v1/user/model-config/probe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(probeBody("CHAT", "DEEPSEEK", "https://api.deepseek.com/v1",
                                "密".repeat(10), false, "deepseek-chat")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(response).contains("\"code\":\"VALIDATION_ERROR\"");
        verify(pythonAiEngineClient, never()).probeModel(anyLong(), any(), any(), any());
    }

    private static String probeBody(String probeType, String provider, String baseUrl,
                                    String apiKey, boolean useSavedKey, String model) throws Exception {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("probe_type", probeType);
        body.put("provider", provider);
        body.put("base_url", baseUrl);
        body.put("use_saved_key", useSavedKey);
        body.put("model_name", model);
        if (apiKey != null) {
            body.put("api_key", apiKey);
        }
        return MAPPER.writeValueAsString(body);
    }

    @Test
    @DisplayName("GET /user/config 返回真实 existing_course_count")
    void getConfigReturnsExistingCourseCount() throws Exception {
        when(userConfigService.findById(USER_ID)).thenReturn(Optional.of(entity()));
        mockMvc = buildMvc(userId -> 3);

        mockMvc.perform(get("/api/v1/user/config"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.data.existing_course_count").value(3));
    }

    @Test
    @DisplayName("已有课程时修改默认 Embedding 未确认迁移：400 且不探测、不落库")
    void rejectsEmbeddingTargetChangeWithoutMigration() throws Exception {
        when(userConfigService.findById(USER_ID)).thenReturn(Optional.of(entity()));
        mockMvc = buildMvc(userId -> 2);

        int status = mockMvc.perform(put("/api/v1/user/model-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saveBody("https://api.deepseek.com/v1", "deepseek-chat", false,
                                "sk-chat", "https://api.siliconflow.cn/v1", "sk-embed",
                                "BAAI/bge-m3-v2", false)))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(400);
        verify(pythonAiEngineClient, never()).probeModel(anyLong(), any(), any(), any());
        verify(userConfigService, never()).updateByokModelConfig(anyLong(), any());
        verify(migrationPort, never()).migrateExistingCourses(anyLong(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("确认迁移后：保存与课程批量切换在同一事务服务内完成")
    void migratesExistingCoursesWhenConfirmed() throws Exception {
        UserConfigEntity current = entity();
        UserConfigEntity updated = entity();
        updated.setDefaultEmbeddingModel("BAAI/bge-m3-v2");
        updated.setDefaultEmbeddingDim(1024);
        when(userConfigService.findById(USER_ID)).thenReturn(Optional.of(current));
        when(pythonAiEngineClient.probeModel(anyLong(), eq(ModelProbeType.BOTH), any(), any()))
                .thenReturn(new PythonAiEngineClient.ProbeResult(true, true, 1024, 20));
        when(userConfigService.updateByokModelConfig(anyLong(), any())).thenReturn(updated);
        when(migrationPort.migrateExistingCourses(USER_ID, "OPENAI_COMPATIBLE",
                "BAAI/bge-m3-v2", 1024)).thenReturn(2);
        mockMvc = buildMvc(userId -> 2);

        int status = mockMvc.perform(put("/api/v1/user/model-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saveBody("https://api.deepseek.com/v1", "deepseek-chat", false,
                                "sk-chat", "https://api.siliconflow.cn/v1", "sk-embed",
                                "BAAI/bge-m3-v2", true)))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(200);
        verify(migrationPort).migrateExistingCourses(USER_ID, "OPENAI_COMPATIBLE",
                "BAAI/bge-m3-v2", 1024);
    }

    private static String saveBody(String chatBaseUrl, String chatModel, boolean reuse,
                                   String chatKey, String embeddingBaseUrl,
                                   String embeddingKey, String embeddingModel) throws Exception {
        return saveBody(chatBaseUrl, chatModel, reuse, chatKey, embeddingBaseUrl,
                embeddingKey, embeddingModel, false);
    }

    private static String saveBody(String chatBaseUrl, String chatModel, boolean reuse,
                                   String chatKey, String embeddingBaseUrl,
                                   String embeddingKey, String embeddingModel,
                                   boolean migrateExistingCourses) throws Exception {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("chat_provider", "OPENAI_COMPATIBLE");
        body.put("chat_base_url", chatBaseUrl);
        body.put("chat_model", chatModel);
        body.put("default_embedding_provider", "OPENAI_COMPATIBLE");
        body.put("embedding_base_url", embeddingBaseUrl);
        body.put("reuse_chat_api_key", reuse);
        body.put("default_embedding_model", embeddingModel);
        body.put("migrate_existing_courses", migrateExistingCourses);
        if (chatKey != null) {
            body.put("chat_api_key", chatKey);
        }
        if (embeddingKey != null) {
            body.put("embedding_api_key", embeddingKey);
        }
        return MAPPER.writeValueAsString(body);
    }

    @Test
    @DisplayName("非 OLLAMA 且未给 Key、未要求使用已保存 Key 时返回 400，且不发起探测")
    void probeWithoutKeyIsRejected() throws Exception {
        when(userConfigService.findById(USER_ID)).thenReturn(Optional.of(entity()));

        String body = mockMvc.perform(post("/api/v1/user/model-config/probe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"probe_type":"CHAT","provider":"DEEPSEEK",
                                 "base_url":"https://api.deepseek.com/v1",
                                 "model_name":"deepseek-chat"}
                                """))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("\"code\":\"VALIDATION_ERROR\"");
        verify(pythonAiEngineClient, never()).probeModel(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("探测失败时返回上游错误码，且绝不调用更新（原配置保持完好）")
    void saveDoesNotPersistWhenProbeFails() throws Exception {
        when(userConfigService.findById(USER_ID)).thenReturn(Optional.of(entity()));
        when(pythonAiEngineClient.probeModel(anyLong(), eq(ModelProbeType.BOTH), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.UPSTREAM_MODEL_ERROR, "upstream down"));

        int status = mockMvc.perform(put("/api/v1/user/model-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"chat_provider":"DEEPSEEK","chat_base_url":"https://api.deepseek.com/v1",
                                 "chat_api_key":"sk-new-12345678","chat_model":"deepseek-chat",
                                 "default_embedding_provider":"OPENAI_COMPATIBLE",
                                 "embedding_base_url":"https://api.siliconflow.cn/v1",
                                 "reuse_chat_api_key":true,
                                 "default_embedding_model":"BAAI/bge-m3"}
                                """))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(502);
        verify(userConfigService, never()).updateByokModelConfig(anyLong(), any());
    }

    @Test
    @DisplayName("保存成功：Key 以 v1: 密文落库，维度用实测值，响应带掩码与 existing_course_count")
    void saveEncryptsKeysAndReturnsMaskedView() throws Exception {
        UserConfigEntity existing = entity();
        existing.setDefaultEmbeddingDim(null);
        when(userConfigService.findById(USER_ID)).thenReturn(Optional.of(existing));
        when(pythonAiEngineClient.probeModel(anyLong(), eq(ModelProbeType.BOTH), any(), any()))
                .thenReturn(new PythonAiEngineClient.ProbeResult(true, true, 768, 42));
        when(userConfigService.updateByokModelConfig(anyLong(), any())).thenAnswer(invocation -> {
            UserConfigEntity updated = entity();
            updated.setDefaultEmbeddingDim(768);
            return updated;
        });

        String body = mockMvc.perform(put("/api/v1/user/model-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"chat_provider":"DEEPSEEK","chat_base_url":"https://api.deepseek.com/v1",
                                 "chat_api_key":"sk-chat-1234567890ab","chat_model":"deepseek-chat",
                                 "default_embedding_provider":"OPENAI_COMPATIBLE",
                                 "embedding_base_url":"https://api.siliconflow.cn/v1",
                                 "reuse_chat_api_key":true,
                                 "default_embedding_model":"BAAI/bge-m3"}
                                """))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        ArgumentCaptor<UserConfigService.ByokModelUpdate> captor =
                ArgumentCaptor.forClass(UserConfigService.ByokModelUpdate.class);
        verify(userConfigService).updateByokModelConfig(eq(USER_ID), captor.capture());
        UserConfigService.ByokModelUpdate update = captor.getValue();
        assertThat(update.chatApiKeyCipher()).startsWith("v1:");
        // reuse_chat_api_key：Embedding 列用同一明文独立加密，因此密文不同但都能解回
        assertThat(update.embeddingApiKeyCipher()).startsWith("v1:")
                .isNotEqualTo(update.chatApiKeyCipher());
        assertThat(update.embeddingDim()).isEqualTo(768);

        assertThat(body).contains("\"default_embedding_dim\":768")
                .contains("\"existing_course_count\":0")
                .doesNotContain("sk-chat-1234567890ab", "v1:");
    }

    @Test
    @DisplayName("公共探测接口拒绝 BOTH：请求体只有一套模型配置，无法表达两套")
    void bothProbeTypeIsRejectedByPublicApi() throws Exception {
        when(userConfigService.findById(USER_ID)).thenReturn(Optional.of(entity()));

        String body = mockMvc.perform(post("/api/v1/user/model-config/probe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"probe_type":"BOTH","provider":"DEEPSEEK",
                                 "base_url":"https://api.deepseek.com/v1",
                                 "api_key":"sk-x","model_name":"deepseek-chat"}
                                """))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("\"code\":\"VALIDATION_ERROR\"");
        verify(pythonAiEngineClient, never()).probeModel(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("提供了新 Key 时不得解密旧密文：旧密文损坏也能用新配置恢复")
    void probeWithNewKeyDoesNotTouchCorruptSavedCipher() throws Exception {
        UserConfigEntity entity = entity();
        entity.setChatApiKeyCipher("v1:corrupted:corrupted");
        when(userConfigService.findById(USER_ID)).thenReturn(Optional.of(entity));
        when(pythonAiEngineClient.probeModel(anyLong(), eq(ModelProbeType.CHAT), any(), any()))
                .thenReturn(new PythonAiEngineClient.ProbeResult(true, false, null, 9));

        String body = mockMvc.perform(post("/api/v1/user/model-config/probe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"probe_type":"CHAT","provider":"DEEPSEEK",
                                 "base_url":"https://api.deepseek.com/v1",
                                 "api_key":"sk-new-recovery-key","use_saved_key":false,
                                 "model_name":"deepseek-chat"}
                                """))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("\"code\":\"OK\"");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<ModelEndpoint> captor = ArgumentCaptor.forClass(ModelEndpoint.class);
        verify(pythonAiEngineClient).probeModel(eq(USER_ID), eq(ModelProbeType.CHAT),
                captor.capture(), isNull());
        assertThat(captor.getValue().apiKey()).isEqualTo("sk-new-recovery-key");
    }

    @Test
    @DisplayName("IM 绑定必须成对：只传平台不传账号返回 400")
    void preferencesRequirePairedImBinding() throws Exception {
        String body = mockMvc.perform(put("/api/v1/user/preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timezone":"Asia/Shanghai","dnd_start_time":"23:30",
                                 "dnd_end_time":"07:30:00","bound_im_platform":"QQ_ONEBOT"}
                                """))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("\"code\":\"VALIDATION_ERROR\"");
        verify(userConfigService, never()).updatePreferences(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("IM 账号被其他用户占用时由唯一索引冲突映射为 409")
    void duplicateImBindingMapsToConflict() throws Exception {
        when(userConfigService.updatePreferences(anyLong(), any(), any(), any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.IM_ACCOUNT_ALREADY_BOUND));

        int status = mockMvc.perform(put("/api/v1/user/preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timezone":"Asia/Shanghai","dnd_start_time":"23:30",
                                 "dnd_end_time":"07:30:00","bound_im_platform":"QQ_ONEBOT",
                                 "bound_im_uid":"24681012"}
                                """))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(409);
    }

    @Test
    @DisplayName("销毁 Key 的 target 只接受 ALL/CHAT/EMBEDDING，非法值返回 400")
    void invalidDeleteTargetIsRejected() throws Exception {
        String body = mockMvc.perform(delete("/api/v1/user/model-config/keys").param("target", "BOTH"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("\"code\":\"VALIDATION_ERROR\"");
        verify(userConfigService, never()).clearApiKeys(anyLong(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean());
    }
}
