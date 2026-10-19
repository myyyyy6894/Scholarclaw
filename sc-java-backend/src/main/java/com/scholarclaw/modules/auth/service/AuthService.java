package com.scholarclaw.modules.auth.service;

import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.modules.auth.dto.AuthTokenResponse;
import com.scholarclaw.modules.auth.dto.LoginRequest;
import com.scholarclaw.modules.auth.dto.RegisterRequest;
import com.scholarclaw.modules.auth.jwt.JwtService;
import com.scholarclaw.modules.userconfig.entity.UserConfigEntity;
import com.scholarclaw.modules.userconfig.service.UserConfigService;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 注册 / 登录 / 登出的业务编排（{@code 04} §4.1）。
 *
 * <p><b>Single-Writer：</b>本类**不持有任何 Mapper**，`t_user_config` 的读写全部经
 * {@link UserConfigService}（`auth` 的 package-info 亦如此约定）。
 *
 * <p><b>身份来源：</b>签发 token 时用的 `user_id` 一律取自数据库回填的主键，绝不读取请求体里的
 * 任何用户标识（{@code 04} §2.7.2）。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private static final String DEFAULT_TIMEZONE = "Asia/Shanghai";
    private static final String ROLE_STUDENT = "STUDENT";
    private static final DateTimeFormatter ISO_MILLIS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    private final UserConfigService userConfigService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final TokenRevocationService tokenRevocationService;

    public AuthService(UserConfigService userConfigService, PasswordEncoder passwordEncoder,
                       JwtService jwtService, TokenRevocationService tokenRevocationService) {
        this.userConfigService = userConfigService;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.tokenRevocationService = tokenRevocationService;
    }

    /**
     * 注册（{@code API-AUTH-001}）。
     *
     * <p>三条与契约一致的行为：① 密码 BCrypt(10) 单向哈希后落库；② **不预建默认课程**
     * （{@code 03} §4.2.1 / §9.3：未配置 Embedding 前严禁伪造模型与维度）；③ 同名并发由唯一索引兜底，
     * 捕获后返回 `409 USER_ALREADY_EXISTS`。
     */
    public AuthTokenResponse register(RegisterRequest request) {
        String timezone = resolveTimezone(request.timezone());
        String passwordHash = passwordEncoder.encode(request.password());
        UserConfigEntity created;
        try {
            created = userConfigService.createUserAccount(request.username(), passwordHash, timezone);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.USER_ALREADY_EXISTS, "该用户名已被注册。");
        }
        JwtService.IssuedToken token = jwtService.issue(created.getUserId(), created.getUsername(), ROLE_STUDENT);
        log.info("用户注册成功 user_id={}", created.getUserId());
        return toResponse(created, token);
    }

    /**
     * 登录（{@code API-AUTH-002}）。
     *
     * <p>用户名不存在与密码错误**返回同一个** `401 AUTH_INVALID_CREDENTIALS`，防止账号枚举；
     * 已注销（`DEACTIVATING` 或 Redis 阻断键）返回 `401 ACCOUNT_DEACTIVATED`。
     */
    public AuthTokenResponse login(LoginRequest request) {
        UserConfigEntity account = userConfigService.findByUsername(request.username())
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS, "用户名或密码错误。"));
        if (!passwordEncoder.matches(request.password(), account.getPasswordHash())) {
            throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS, "用户名或密码错误。");
        }
        if (UserConfigService.isDeactivating(account) || tokenRevocationService.isDeactivated(account.getUserId())) {
            throw new BusinessException(ErrorCode.ACCOUNT_DEACTIVATED, "该账号已注销。");
        }
        JwtService.IssuedToken token = jwtService.issue(account.getUserId(), account.getUsername(), ROLE_STUDENT);
        log.info("用户登录成功 user_id={}", account.getUserId());
        return toResponse(account, token);
    }

    /**
     * 登出（{@code API-AUTH-003}）：把当前 token 的 `jti` 写入黑名单，TTL = 剩余有效期。
     *
     * <p>重复登出幂等（同 key 重复写入）。`jti`/`exp` 都来自**验签通过的** token（由过滤器放入 principal）。
     */
    public void logout(Long userId, String tokenId, java.time.Instant expiresAt) {
        tokenRevocationService.revoke(userId, tokenId, expiresAt);
        log.info("用户登出 user_id={}", userId);
    }

    private static String resolveTimezone(String requested) {
        String timezone = requested == null || requested.isBlank() ? DEFAULT_TIMEZONE : requested.trim();
        try {
            ZoneId.of(timezone);
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "timezone 不是合法的 IANA 时区标识。");
        }
        return timezone;
    }

    private static AuthTokenResponse toResponse(UserConfigEntity account, JwtService.IssuedToken token) {
        return new AuthTokenResponse(
                account.getUserId(),
                account.getUsername(),
                account.getAccountStatus(),
                token.token(),
                "Bearer",
                token.expiresInSeconds(),
                // 是否已配置 BYOK：TASK-203 才有写入路径，注册时必然为 false（04 契约要求此处固定 false）
                account.getChatModel() != null && account.getChatApiKeyCipher() != null,
                account.getCreatedAt() == null ? null
                        : account.getCreatedAt().atZone(java.time.ZoneId.of(account.getTimezone())).format(ISO_MILLIS));
    }
}
