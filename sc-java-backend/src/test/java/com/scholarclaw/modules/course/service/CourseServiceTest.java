package com.scholarclaw.modules.course.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scholarclaw.common.client.ModelEndpoint;
import com.scholarclaw.common.client.ModelProbeType;
import com.scholarclaw.common.client.PythonAiEngineClient;
import com.scholarclaw.common.enums.IndexStatus;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.common.security.AesGcmCryptoService;
import com.scholarclaw.common.security.BaseUrlSecurityValidator;
import com.scholarclaw.modules.course.dto.ChangeEmbeddingModelRequest;
import com.scholarclaw.modules.course.dto.ChangeEmbeddingModelVO;
import com.scholarclaw.modules.course.dto.CourseNamespaceVO;
import com.scholarclaw.modules.course.dto.CreateCourseRequest;
import com.scholarclaw.modules.course.entity.CourseNamespaceEntity;
import com.scholarclaw.modules.course.mapper.CourseNamespaceMapper;
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import com.scholarclaw.modules.userconfig.service.ModelConfigRules;
import com.scholarclaw.modules.userconfig.service.ModelKeyBindingRules;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;

/** TASK-401 课程空间与 Embedding 绑定的领域行为。 */
class CourseServiceTest {

    private static final long USER_ID = 10001L;
    private static final long COURSE_ID = 2001L;

    private CourseNamespaceMapper mapper;
    private CourseMutationService mutationService;
    private UserConfigService userConfigService;
    private PythonAiEngineClient aiClient;
    private AesGcmCryptoService cryptoService;
    private ObjectProvider<CourseDocumentStatsProvider> statsProvider;
    private CourseService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mapper = mock(CourseNamespaceMapper.class);
        mutationService = mock(CourseMutationService.class);
        userConfigService = mock(UserConfigService.class);
        aiClient = mock(PythonAiEngineClient.class);
        cryptoService = mock(AesGcmCryptoService.class);
        statsProvider = mock(ObjectProvider.class);
        lenient().when(statsProvider.getIfAvailable()).thenReturn(null);
        service = new CourseService(
                mapper,
                mutationService,
                userConfigService,
                new ModelConfigRules(false, ""),
                new ModelKeyBindingRules(),
                aiClient,
                mock(BaseUrlSecurityValidator.class),
                cryptoService,
                statsProvider);
    }

    @Test
    @DisplayName("未配置完整 Embedding 时拒绝建课，且不写库")
    void rejectsCreateWhenEmbeddingConfigInvalid() {
        UserConfigEntity config = validConfig();
        config.setDefaultEmbeddingDim(null);
        when(userConfigService.findById(USER_ID)).thenReturn(Optional.of(config));

        assertThatThrownBy(() -> service.createCourse(
                USER_ID, new CreateCourseRequest("操作系统原理", null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.E_SYS_01));
        verify(mutationService, never()).create(any());
    }

    @Test
    @DisplayName("建课复制已验证的 Embedding 三元组，并强制 is_default=0")
    void createCopiesVerifiedEmbeddingTriplet() {
        UserConfigEntity config = validConfig();
        when(userConfigService.findById(USER_ID)).thenReturn(Optional.of(config));
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mutationService.create(any())).thenAnswer(invocation -> {
            CourseNamespaceEntity entity = invocation.getArgument(0);
            entity.setCourseId(COURSE_ID);
            return entity;
        });

        CourseNamespaceVO vo = service.createCourse(
                USER_ID, new CreateCourseRequest("操作系统原理", " 2026 秋 "));

        assertThat(vo.courseId()).isEqualTo(COURSE_ID);
        assertThat(vo.isDefault()).isFalse();
        assertThat(vo.embeddingProvider()).isEqualTo("OPENAI_COMPATIBLE");
        assertThat(vo.embeddingModel()).isEqualTo("BAAI/bge-m3");
        assertThat(vo.embeddingDim()).isEqualTo(1024);
        assertThat(vo.indexVersion()).isEqualTo(1);
        assertThat(vo.indexStatus()).isEqualTo(IndexStatus.ACTIVE.name());
    }

    @Test
    @DisplayName("同名建课映射为 COURSE_NAME_DUPLICATE（含唯一键并发兜底）")
    void mapsDuplicateCourseName() {
        when(userConfigService.findById(USER_ID)).thenReturn(Optional.of(validConfig()));
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mutationService.create(any())).thenThrow(new DuplicateKeyException("uk"));

        assertThatThrownBy(() -> service.createCourse(
                USER_ID, new CreateCourseRequest("操作系统原理", null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.COURSE_NAME_DUPLICATE));
    }

    @Test
    @DisplayName("拒绝手动创建保留名“未分类课程”")
    void rejectsReservedDefaultName() {
        assertThatThrownBy(() -> service.createCourse(
                USER_ID, new CreateCourseRequest("未分类课程", null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    @DisplayName("切换模型先探测真实维度，成功后 index_version+1")
    void changeEmbeddingProbesAndIncrementsVersion() {
        when(mapper.selectOne(any())).thenReturn(activeCourse(IndexStatus.ACTIVE.name(), 1));
        UserConfigEntity config = validConfig();
        config.setEmbeddingApiKeyCipher("v1:iv:cipher");
        when(userConfigService.findById(USER_ID)).thenReturn(Optional.of(config));
        when(cryptoService.decrypt("v1:iv:cipher")).thenReturn("sk-embedding");
        when(aiClient.probeModel(eq(USER_ID), eq(ModelProbeType.EMBEDDING), eq(null), any(ModelEndpoint.class)))
                .thenReturn(new PythonAiEngineClient.ProbeResult(false, true, 1536, 20));
        CourseNamespaceEntity switched = activeCourse(IndexStatus.ACTIVE.name(), 2);
        when(mutationService.applyEmbeddingChange(eq(USER_ID), eq(COURSE_ID),
                eq("OPENAI_COMPATIBLE"), eq("BAAI/bge-m3"), eq(1536), eq(1)))
                .thenReturn(new CourseMutationService.EmbeddingChangeResult(switched, 0));

        ChangeEmbeddingModelVO vo = service.changeEmbeddingModel(USER_ID, COURSE_ID,
                new ChangeEmbeddingModelRequest(true, null, null));

        assertThat(vo.previousIndexVersion()).isEqualTo(1);
        assertThat(vo.currentIndexVersion()).isEqualTo(2);
        assertThat(vo.embeddingDim()).isEqualTo(1536);
        assertThat(vo.reindexingDocCount()).isZero();
    }

    @Test
    @DisplayName("显式指定与已保存目标完全一致的模型时允许复用旧 Key")
    void allowsExplicitSameTarget() {
        when(mapper.selectOne(any())).thenReturn(activeCourse(IndexStatus.ACTIVE.name(), 1));
        UserConfigEntity config = validConfig();
        config.setEmbeddingApiKeyCipher("v1:iv:cipher");
        when(userConfigService.findById(USER_ID)).thenReturn(Optional.of(config));
        when(cryptoService.decrypt("v1:iv:cipher")).thenReturn("sk-embedding");
        when(aiClient.probeModel(anyLong(), any(), any(), any()))
                .thenReturn(new PythonAiEngineClient.ProbeResult(false, true, 1024, 20));
        when(mutationService.applyEmbeddingChange(anyLong(), anyLong(), anyString(), anyString(),
                anyInt(), anyInt()))
                .thenReturn(new CourseMutationService.EmbeddingChangeResult(
                        activeCourse(IndexStatus.ACTIVE.name(), 2), 0));

        ChangeEmbeddingModelVO vo = service.changeEmbeddingModel(USER_ID, COURSE_ID,
                new ChangeEmbeddingModelRequest(false, "OPENAI_COMPATIBLE", "BAAI/bge-m3"));

        assertThat(vo.embeddingModel()).isEqualTo("BAAI/bge-m3");
        verify(cryptoService).decrypt("v1:iv:cipher");
    }

    @Test
    @DisplayName("仅大小写不同的模型名被拒绝，且不解密、不探测")
    void rejectsCaseOnlyModelChange() {
        when(mapper.selectOne(any())).thenReturn(activeCourse(IndexStatus.ACTIVE.name(), 1));
        UserConfigEntity config = validConfig();
        config.setEmbeddingApiKeyCipher("v1:iv:cipher");
        when(userConfigService.findById(USER_ID)).thenReturn(Optional.of(config));

        assertThatThrownBy(() -> service.changeEmbeddingModel(USER_ID, COURSE_ID,
                new ChangeEmbeddingModelRequest(false, "OPENAI_COMPATIBLE", "baai/BGE-M3")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
        verify(cryptoService, never()).decrypt(anyString());
        verify(aiClient, never()).probeModel(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("provider 不同被拒绝，旧 Key 不进入探测请求")
    void rejectsDifferentProviderWithoutDecrypting() {
        when(mapper.selectOne(any())).thenReturn(activeCourse(IndexStatus.ACTIVE.name(), 1));
        UserConfigEntity config = validConfig();
        config.setEmbeddingApiKeyCipher("v1:iv:cipher");
        when(userConfigService.findById(USER_ID)).thenReturn(Optional.of(config));

        assertThatThrownBy(() -> service.changeEmbeddingModel(USER_ID, COURSE_ID,
                new ChangeEmbeddingModelRequest(false, "ZHIPU", "BAAI/bge-m3")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
        verify(cryptoService, never()).decrypt(anyString());
        verify(aiClient, never()).probeModel(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("REBUILDING 期间拒绝重复切换，不发起探测")
    void rejectsSwitchWhileRebuilding() {
        when(mapper.selectOne(any())).thenReturn(activeCourse(IndexStatus.REBUILDING.name(), 2));

        assertThatThrownBy(() -> service.changeEmbeddingModel(USER_ID, COURSE_ID,
                new ChangeEmbeddingModelRequest(true, null, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.E_SYS_03));
        verify(aiClient, never()).probeModel(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("探测维度无效时拒绝落库，原课程保持不变")
    void keepsCourseWhenProbeDimensionInvalid() {
        when(mapper.selectOne(any())).thenReturn(activeCourse(IndexStatus.ACTIVE.name(), 1));
        UserConfigEntity config = validConfig();
        config.setEmbeddingApiKeyCipher("v1:iv:cipher");
        when(userConfigService.findById(USER_ID)).thenReturn(Optional.of(config));
        when(cryptoService.decrypt(anyString())).thenReturn("sk-embedding");
        when(aiClient.probeModel(anyLong(), any(), any(), any()))
                .thenReturn(new PythonAiEngineClient.ProbeResult(false, false, null, 5));

        assertThatThrownBy(() -> service.changeEmbeddingModel(USER_ID, COURSE_ID,
                new ChangeEmbeddingModelRequest(true, null, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.UPSTREAM_MODEL_ERROR));
        verify(mutationService, never()).applyEmbeddingChange(
                anyLong(), anyLong(), anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("删除课程先做归属校验，再发布软删事务")
    void deleteRequiresOwnership() {
        when(mapper.selectOne(any())).thenReturn(activeCourse(IndexStatus.ACTIVE.name(), 1));

        service.deleteCourse(USER_ID, COURSE_ID);

        verify(mutationService).softDelete(USER_ID, COURSE_ID);
    }

    @Test
    @DisplayName("默认课程并发冲突后回查胜出线程的行")
    void defaultCourseRecoversFromDuplicateKey() {
        CourseNamespaceEntity winner = activeCourse(IndexStatus.ACTIVE.name(), 1);
        winner.setCourseId(3001L);
        winner.setIsDefault(1);
        when(mapper.selectActiveDefaultCourse(USER_ID)).thenReturn(null, winner);
        when(userConfigService.findById(USER_ID)).thenReturn(Optional.of(validConfig()));
        when(mutationService.insertDefault(any())).thenThrow(new DuplicateKeyException("uk_course_user_default"));

        CourseNamespaceEntity result = service.getOrCreateDefaultCourse(USER_ID);

        assertThat(result).isSameAs(winner);
    }

    @Test
    @DisplayName("课程数量只统计当前用户未删除课程")
    void countsOnlyActiveCoursesOfCurrentUser() {
        when(mapper.selectCount(any())).thenReturn(3L);

        assertThat(service.countActiveCourses(USER_ID)).isEqualTo(3);
    }

    private static UserConfigEntity validConfig() {
        UserConfigEntity config = new UserConfigEntity();
        config.setUserId(USER_ID);
        config.setAccountStatus("ACTIVE");
        config.setTimezone("Asia/Shanghai");
        config.setDefaultEmbeddingProvider("OPENAI_COMPATIBLE");
        config.setEmbeddingBaseUrl("https://api.siliconflow.cn/v1");
        config.setDefaultEmbeddingModel("BAAI/bge-m3");
        config.setDefaultEmbeddingDim(1024);
        config.setEmbeddingApiKeyCipher("v1:iv:cipher");
        config.setUpdatedAt(LocalDateTime.now());
        return config;
    }

    private static CourseNamespaceEntity activeCourse(String status, int version) {
        CourseNamespaceEntity entity = new CourseNamespaceEntity();
        entity.setCourseId(COURSE_ID);
        entity.setUserId(USER_ID);
        entity.setCourseName("操作系统原理");
        entity.setIsDefault(0);
        entity.setEmbeddingProvider("OPENAI_COMPATIBLE");
        entity.setEmbeddingModel("BAAI/bge-m3");
        entity.setEmbeddingDim(1024);
        entity.setIndexVersion(version);
        entity.setIndexStatus(status);
        entity.setIsDeleting(0);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        return entity;
    }
}
