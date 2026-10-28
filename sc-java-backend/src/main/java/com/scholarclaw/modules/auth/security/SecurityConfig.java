package com.scholarclaw.modules.auth.security;

import com.scholarclaw.common.exception.ErrorCode;
import com.scholarclaw.common.security.SecurityErrorResponder;
import com.scholarclaw.modules.auth.jwt.JwtService;
import com.scholarclaw.modules.auth.service.TokenRevocationService;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 6 的无状态鉴权配置（{@code 02} §10.1、{@code 04} §2.7.1）。
 *
 * <p><b>只开放两类匿名入口：</b>注册与登录（{@code 04} §2.1 明确二者允许匿名），
 * 外加 `/internal/**`——内部接口的保护由 `InternalTokenAuthFilter` 以常量时间比较负责，
 * 不重复套一层 JWT（Python 服务没有 JWT）。
 *
 * <p><b>关掉的默认行为：</b>CSRF（纯 Bearer API + 前后端分离）、表单登录、HTTP Basic、Spring 自己的
 * `/logout`（登出由 `AuthController` 实现并写 Redis 黑名单）、Session（无状态）。
 *
 * <p><b>错误响应：</b>401/403 也必须是 `Result<T>` 信封（{@code 04} §2.3），由
 * {@link SecurityErrorResponder} 在过滤器链里直接写。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwtService,
                                                   TokenRevocationService tokenRevocationService,
                                                   SecurityErrorResponder responder) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // SSE 完成/异常时会触发 ASYNC 二次分派；此时首次 HTTP 请求已完成 JWT 鉴权，
                        // 若再次按 anyRequest().authenticated() 鉴权，会在响应已提交后抛
                        // AccessDeniedException，表现为 Nginx upstream prematurely closed。
                        // 这里只放行二次分派，首次 REQUEST 仍必须通过 JWT。
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers("/api/v1/auth/register", "/api/v1/auth/login").permitAll()
                        // OneBot Webhook 使用 Bearer <SC_ONEBOT_ACCESS_TOKEN>，由 channel 模块
                        // 常量时间校验；不能套 JWT 规则（NapCat 没有用户 JWT）。
                        .requestMatchers("/webhooks/onebot/v11").permitAll()
                        // 内部接口由 InternalTokenAuthFilter 保护（Python 没有 JWT，不能套 Bearer 规则）
                        .requestMatchers("/internal/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, exception) ->
                                responder.respond(response, ErrorCode.AUTH_UNAUTHORIZED, "未登录或登录状态已失效，请重新登录。"))
                        .accessDeniedHandler((request, response, exception) ->
                                responder.respond(response, ErrorCode.AUTH_FORBIDDEN)))
                .addFilterBefore(new JwtAuthenticationFilter(jwtService, tokenRevocationService, responder),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** 密码哈希：BCrypt strength 10（{@code 03} §4.1 / §7.3 明文密码零落库）。 */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }
}
