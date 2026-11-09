package com.scholarclaw.modules.schedule.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.common.security.AesGcmCryptoService;
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import com.scholarclaw.modules.userconfig.service.ModelConfigRules;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ScheduleImageModelResolverTest {
    private final UserConfigService users = mock(UserConfigService.class);
    private final ModelConfigRules rules = new ModelConfigRules(false, "");
    private final AesGcmCryptoService crypto = mock(AesGcmCryptoService.class);
    private final ScheduleImageModelResolver resolver = new ScheduleImageModelResolver(users, rules, crypto, new ObjectMapper());

    @Test
    void displaysOnlyOriginAndBindsConsentToAllModelSettings() {
        UserConfigEntity user = config();
        when(users.findById(42L)).thenReturn(Optional.of(user));
        when(crypto.decrypt("cipher-1")).thenReturn("private-key");
        var selection = resolver.selection(42);
        assertThat(selection.origin()).isEqualTo("https://vision.example.com");
        assertThat(selection.modelName()).isEqualTo("model-a");
        assertThat(selection.confirmationToken()).matches("[0-9a-f]{64}");
        verify(crypto, never()).decrypt(anyString());
        assertThat(resolver.endpoint(42, selection.confirmationToken()).apiKey()).isEqualTo("private-key");

        user.setChatApiKeyCipher("cipher-2");
        assertCode(() -> resolver.endpoint(42, selection.confirmationToken()), ErrorCode.STATE_CONFLICT);
        user.setChatApiKeyCipher("cipher-1"); user.setChatModel("different-model");
        assertCode(() -> resolver.endpoint(42, selection.confirmationToken()), ErrorCode.STATE_CONFLICT);
        user.setChatModel("model-a"); user.setChatBaseUrl("https://other.example.com/v1");
        assertCode(() -> resolver.endpoint(42, selection.confirmationToken()), ErrorCode.STATE_CONFLICT);
        verify(crypto, times(1)).decrypt(anyString());
    }

    @Test
    void rejectsMissingConfigDeactivatedAccountAndCredentialBearingUrl() {
        UserConfigEntity user = config();
        user.setAccountStatus("DEACTIVATING");
        assertCode(() -> resolver.selection(user), ErrorCode.ACCOUNT_DEACTIVATED);
        user.setAccountStatus("ACTIVE"); user.setChatApiKeyCipher(null);
        assertCode(() -> resolver.selection(user), ErrorCode.E_SYS_01);
        user.setChatApiKeyCipher("cipher-1"); user.setChatBaseUrl("https://secret@example.com/v1");
        assertCode(() -> resolver.selection(user), ErrorCode.E_SYS_02);
    }

    @Test
    void decryptionFailuresDoNotExposeKeyOrCipher() {
        UserConfigEntity user = config();
        var token = resolver.selection(user).confirmationToken();
        when(crypto.decrypt(anyString())).thenThrow(new IllegalStateException("secret-decrypt-detail"));
        assertThatThrownBy(() -> resolver.endpoint(user, token)).isInstanceOf(BusinessException.class)
                .hasMessageNotContaining("secret-decrypt-detail").hasMessageNotContaining("cipher-1");
    }

    private static UserConfigEntity config() {
        var user = new UserConfigEntity();
        user.setAccountStatus("ACTIVE"); user.setChatProvider("DEEPSEEK");
        user.setChatBaseUrl("https://vision.example.com/v1?token=do-not-display");
        user.setChatModel("model-a"); user.setChatApiKeyCipher("cipher-1");
        return user;
    }
    private static void assertCode(Runnable operation, ErrorCode code) {
        assertThatThrownBy(operation::run).isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode()).isEqualTo(code));
    }
}
