package com.scholarclaw.modules.userconfig.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ModelConfigSaveServiceTest {

    private static final long USER_ID = 10001L;
    private static final LocalDateTime UPDATED_AT = LocalDateTime.of(2026, 10, 23, 12, 0, 0, 123_000_000);

    private UserConfigService userConfigService;
    private CourseEmbeddingMigrationPort migrationPort;
    private UserCourseCountProvider countProvider;
    private ModelConfigSaveService service;

    @BeforeEach
    void setUp() {
        userConfigService = mock(UserConfigService.class);
        migrationPort = mock(CourseEmbeddingMigrationPort.class);
        countProvider = mock(UserCourseCountProvider.class);
        service = new ModelConfigSaveService(userConfigService, migrationPort, countProvider);
        when(userConfigService.lockForUpdate(USER_ID)).thenReturn(locked("embed"));
    }

    @Test
    @DisplayName("锁内重新计算目标变化：未确认迁移时拒绝覆盖 Key")
    void rejectsWhenCoursesAppearedBeforeTransaction() {
        when(countProvider.countActiveCourses(USER_ID)).thenReturn(1);

        assertThatThrownBy(() -> service.saveAndMaybeMigrate(USER_ID, update("embed-v2"), false,
                "embed-v2", 8, UPDATED_AT))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
        verify(userConfigService).lockForUpdate(USER_ID);
        verify(userConfigService, never()).updateByokModelConfig(any(), any());
    }

    @Test
    @DisplayName("确认迁移时保存并迁移全部课程")
    void savesAndMigratesInLockOrder() {
        when(countProvider.countActiveCourses(USER_ID)).thenReturn(2);
        UserConfigEntity updated = new UserConfigEntity();
        when(userConfigService.updateByokModelConfig(any(), any())).thenReturn(updated);

        assertThat(service.saveAndMaybeMigrate(USER_ID, update("embed-v2"), true,
                "embed-v2", 8, UPDATED_AT)).isSameAs(updated);

        verify(migrationPort).migrateExistingCourses(USER_ID, "OPENAI_COMPATIBLE", "embed-v2", 8);
    }

    @Test
    @DisplayName("锁内发现配置快照已变化：返回状态冲突，不覆盖")
    void rejectsStaleSnapshot() {
        assertThatThrownBy(() -> service.saveAndMaybeMigrate(USER_ID, update("embed-v2"), true,
                "embed-v2", 8, UPDATED_AT.minusSeconds(1)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.STATE_CONFLICT));
        verify(userConfigService).lockForUpdate(USER_ID);
        verify(userConfigService, never()).updateByokModelConfig(any(), any());
    }

    @Test
    @DisplayName("目标未变化时不做迁移")
    void doesNotMigrateWhenTargetUnchanged() {
        when(countProvider.countActiveCourses(USER_ID)).thenReturn(2);
        when(userConfigService.updateByokModelConfig(any(), any())).thenReturn(new UserConfigEntity());

        service.saveAndMaybeMigrate(USER_ID, update("embed"), false, "embed", 8, UPDATED_AT);

        verify(migrationPort, never()).migrateExistingCourses(any(), any(), any(), anyInt());
    }

    private static UserConfigEntity locked(String model) {
        UserConfigEntity entity = new UserConfigEntity();
        entity.setDefaultEmbeddingProvider("OPENAI_COMPATIBLE");
        entity.setEmbeddingBaseUrl("https://example.com/v1");
        entity.setDefaultEmbeddingModel(model);
        entity.setUpdatedAt(UPDATED_AT);
        return entity;
    }

    private static UserConfigService.ByokModelUpdate update(String embeddingModel) {
        return new UserConfigService.ByokModelUpdate("OPENAI_COMPATIBLE",
                "https://example.com/v1", "chat", null, "OPENAI_COMPATIBLE",
                "https://example.com/v1", embeddingModel, 8, "cipher");
    }
}
