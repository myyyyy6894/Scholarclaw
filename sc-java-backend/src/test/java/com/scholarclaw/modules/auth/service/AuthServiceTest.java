package com.scholarclaw.modules.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.auth.dto.AuthTokenResponse;
import com.scholarclaw.modules.auth.dto.LoginRequest;
import com.scholarclaw.modules.auth.dto.RegisterRequest;
import com.scholarclaw.modules.auth.jwt.JwtService;
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 注册 / 登录 / 登出的业务规则（{@code 04} §4.1）。
 *
 * <p>只测编排与错误码映射：真正的落库、JWT 往返与登出后吊销在容器 E2E 里验证；
 * 这里锁住"用户名重复→409、密码错→401、注销→401、密码不明文"这些不可退让的规则。
 */
class AuthServiceTest {

    private static final String SECRET = "unit-test-only-jwt-secret-0123456789abcdef";

    private final UserConfigService userConfigService = mock(UserConfigService.class);
    private final TokenRevocationService revocation = mock(TokenRevocationService.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    private final JwtService jwtService = new JwtService(SECRET, 604800);

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userConfigService, passwordEncoder, jwtService, revocation);
    }

    private static UserConfigEntity account(String passwordHash, String status) {
        UserConfigEntity entity = new UserConfigEntity();
        entity.setUserId(10001L);
        entity.setUsername("zhangsan_cs");
        entity.setPasswordHash(passwordHash);
        entity.setAccountStatus(status);
        entity.setTimezone("Asia/Shanghai");
        entity.setKeyVersion("v1");
        entity.setCreatedAt(LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
        entity.setDndStartTime(LocalTime.of(23, 30));
        entity.setDndEndTime(LocalTime.of(7, 30));
        return entity;
    }

    private static ErrorCode errorCodeOf(Throwable e) {
        return ((BusinessException) e).getErrorCode();
    }

    @Test
    @DisplayName("注册成功：密码以 BCrypt 哈希落库、返回 7 天 token、has_byok_config=false")
    void registerHashesPasswordAndIssuesToken() {
        when(userConfigService.createUserAccount(anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String hash = invocation.getArgument(1);
                    assertThat(hash).startsWith("$2").isNotEqualTo("Password2026");
                    return account(hash, "ACTIVE");
                });

        AuthTokenResponse response = authService.register(
                new RegisterRequest("zhangsan_cs", "Password2026", "Asia/Shanghai"));

        assertThat(response.userId()).isEqualTo(10001L);
        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(604800L);
        assertThat(response.hasByokConfig()).isFalse();
        assertThat(response.accountStatus()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("注册同名用户：把唯一键冲突映射为 409 USER_ALREADY_EXISTS")
    void registerMapsDuplicateKeyToConflict() {
        when(userConfigService.createUserAccount(anyString(), anyString(), anyString()))
                .thenThrow(new DuplicateKeyException("uk_user_username"));

        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("zhangsan_cs", "Password2026", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(AuthServiceTest::errorCodeOf)
                .isEqualTo(ErrorCode.USER_ALREADY_EXISTS);
    }

    @Test
    @DisplayName("非法时区：400 VALIDATION_ERROR（不落库）")
    void registerRejectsInvalidTimezone() {
        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("zhangsan_cs", "Password2026", "Invalid/Zone")))
                .isInstanceOf(BusinessException.class)
                .extracting(AuthServiceTest::errorCodeOf)
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    @DisplayName("登录：用户不存在与密码错误返回同一个 401 AUTH_INVALID_CREDENTIALS（防账号枚举）")
    void loginUsesSingleErrorForUnknownUserAndWrongPassword() {
        when(userConfigService.findByUsername("zhangsan_cs")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> authService.login(new LoginRequest("zhangsan_cs", "whatever1")))
                .isInstanceOf(BusinessException.class)
                .extracting(AuthServiceTest::errorCodeOf)
                .isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIALS);

        when(userConfigService.findByUsername("lisi_cs"))
                .thenReturn(Optional.of(account(passwordEncoder.encode("Correct2026"), "ACTIVE")));
        assertThatThrownBy(() -> authService.login(new LoginRequest("lisi_cs", "Wrong2026")))
                .isInstanceOf(BusinessException.class)
                .extracting(AuthServiceTest::errorCodeOf)
                .isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIALS);
    }

    @Test
    @DisplayName("登录成功返回 token；账号注销中或 Redis 有阻断键 → 401 ACCOUNT_DEACTIVATED")
    void loginIssuesTokenOrRejectsDeactivatedAccount() {
        String hash = passwordEncoder.encode("Correct2026");
        when(userConfigService.findByUsername("zhangsan_cs"))
                .thenReturn(Optional.of(account(hash, "ACTIVE")));
        when(revocation.isDeactivated(10001L)).thenReturn(false);

        assertThat(authService.login(new LoginRequest("zhangsan_cs", "Correct2026")).accessToken())
                .isNotBlank();

        when(userConfigService.findByUsername("deactivating_cs"))
                .thenReturn(Optional.of(account(hash, "DEACTIVATING")));
        assertThatThrownBy(() -> authService.login(new LoginRequest("deactivating_cs", "Correct2026")))
                .isInstanceOf(BusinessException.class)
                .extracting(AuthServiceTest::errorCodeOf)
                .isEqualTo(ErrorCode.ACCOUNT_DEACTIVATED);

        when(userConfigService.findByUsername("blocked_cs"))
                .thenReturn(Optional.of(account(hash, "ACTIVE")));
        when(revocation.isDeactivated(10001L)).thenReturn(true);
        assertThatThrownBy(() -> authService.login(new LoginRequest("blocked_cs", "Correct2026")))
                .isInstanceOf(BusinessException.class)
                .extracting(AuthServiceTest::errorCodeOf)
                .isEqualTo(ErrorCode.ACCOUNT_DEACTIVATED);
    }

    @Test
    @DisplayName("登出：把当前 jti 交给吊销服务（TTL 由服务按剩余有效期计算）")
    void logoutDelegatesToRevocation() {
        Instant expiresAt = Instant.now().plusSeconds(3600);

        authService.logout(10001L, "jti-1", expiresAt);

        verify(revocation).revoke(10001L, "jti-1", expiresAt);
    }
}
