package com.scholarclaw.modules.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.common.security.AesGcmCryptoService;
import com.scholarclaw.modules.agent.service.RagModelConfigService.RagModelConfig;
import com.scholarclaw.modules.course.entity.CourseNamespaceEntity;
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import com.scholarclaw.modules.userconfig.service.ModelConfigRules;
import com.scholarclaw.modules.userconfig.service.ModelKeyBindingRules;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RagModelConfigServiceTest {

    private static final long USER_ID = 10001L;

    private UserConfigService userConfigService;
    private AesGcmCryptoService cryptoService;
    private RagModelConfigService service;

    @BeforeEach
    void setUp() {
        userConfigService = mock(UserConfigService.class);
        cryptoService = mock(AesGcmCryptoService.class);
        service = new RagModelConfigService(userConfigService,
                new ModelConfigRules(false, ""), new ModelKeyBindingRules(), cryptoService);
    }

    @Test
    @DisplayName("完整配置解密对话与课程 Embedding Key")
    void resolvesBothEndpoints() {
        when(userConfigService.findById(USER_ID)).thenReturn(Optional.of(config()));
        when(cryptoService.decrypt("cipher-chat")).thenReturn("sk-chat");
        when(cryptoService.decrypt("cipher-embed")).thenReturn("sk-embed");

        RagModelConfig config = service.resolve(USER_ID, course());

        assertThat(config.chat().provider()).isEqualTo("OPENAI_COMPATIBLE");
        assertThat(config.chat().apiKey()).isEqualTo("sk-chat");
        assertThat(config.embedding().modelName()).isEqualTo("embed");
        assertThat(config.embedding().apiKey()).isEqualTo("sk-embed");
        assertThat(config.embeddingDim()).isEqualTo(1024);
    }

    @Test
    @DisplayName("对话配置不完整返回 E-SYS-01")
    void rejectsIncompleteChatConfig() {
        UserConfigEntity config = config();
        config.setChatModel(null);
        when(userConfigService.findById(USER_ID)).thenReturn(Optional.of(config));

        assertThatThrownBy(() -> service.resolve(USER_ID, course()))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.E_SYS_01));
    }

    @Test
    @DisplayName("课程 Embedding 与已保存 Key 目标不一致时拒绝")
    void rejectsEmbeddingKeyTargetMismatch() {
        UserConfigEntity config = config();
        config.setDefaultEmbeddingModel("another-model");
        when(userConfigService.findById(USER_ID)).thenReturn(Optional.of(config));

        assertThatThrownBy(() -> service.resolve(USER_ID, course()))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.E_SYS_01));
    }

    @Test
    @DisplayName("白名单本地 Ollama 允许无 Key")
    void allowsWhitelistedOllamaWithoutKey() {
        RagModelConfigService ollamaService = new RagModelConfigService(userConfigService,
                new ModelConfigRules(true, "http://127.0.0.1:11434"),
                new ModelKeyBindingRules(), cryptoService);
        UserConfigEntity config = config();
        config.setChatProvider("OLLAMA");
        config.setChatBaseUrl("http://127.0.0.1:11434/v1");
        config.setChatApiKeyCipher(null);
        config.setDefaultEmbeddingProvider("OLLAMA");
        config.setEmbeddingBaseUrl("http://127.0.0.1:11434/v1");
        config.setEmbeddingApiKeyCipher(null);
        CourseNamespaceEntity course = course();
        course.setEmbeddingProvider("OLLAMA");
        course.setEmbeddingModel("nomic-embed-text");
        when(userConfigService.findById(USER_ID)).thenReturn(Optional.of(config));

        RagModelConfig resolved = ollamaService.resolve(USER_ID, course);

        assertThat(resolved.chat().apiKey()).isNull();
        assertThat(resolved.embedding().apiKey()).isNull();
    }

    private static UserConfigEntity config() {
        UserConfigEntity config = new UserConfigEntity();
        config.setUserId(USER_ID);
        config.setAccountStatus("ACTIVE");
        config.setChatProvider("OPENAI_COMPATIBLE");
        config.setChatBaseUrl("https://api.example.com/v1");
        config.setChatModel("chat");
        config.setChatApiKeyCipher("cipher-chat");
        config.setDefaultEmbeddingProvider("OPENAI_COMPATIBLE");
        config.setEmbeddingBaseUrl("https://api.example.com/v1");
        config.setDefaultEmbeddingModel("embed");
        config.setDefaultEmbeddingDim(1024);
        config.setEmbeddingApiKeyCipher("cipher-embed");
        return config;
    }

    private static CourseNamespaceEntity course() {
        CourseNamespaceEntity course = new CourseNamespaceEntity();
        course.setCourseId(2001L);
        course.setUserId(USER_ID);
        course.setCourseName("数据库系统概念");
        course.setEmbeddingProvider("OPENAI_COMPATIBLE");
        course.setEmbeddingModel("embed");
        course.setEmbeddingDim(1024);
        course.setIndexVersion(3);
        course.setIndexStatus("ACTIVE");
        return course;
    }
}
