package com.scholarclaw.common.security;

import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 自定义 Base URL 的第一道 SSRF 校验（{@code NFR-SEC-05}、{@code 02} §10.3、{@code 04} §4.2.2）。
 *
 * <p><b>分工（`04` §4.2.2 规则 1/2）：</b>本类在 Java 侧做**字面与主机名层面**的校验
 * （协议、端口、凭据、路径穿越、容器名、云元数据地址、内网地址字面量）；Python 在真正发起出站
 * 请求前会**再做一次 DNS 解析后的 IP 校验**——域名可能在两次校验之间指向内网（DNS rebinding），
 * 只信一次解析结果的做法会漏。
 *
 * <p><b>本地模型白名单：</b>只有 {@code SC_ALLOW_LOCAL_MODEL_BASE_URL=true} 且该**精确 origin**
 * 命中 {@code SC_LOCAL_MODEL_ALLOWED_ORIGINS} 时，才允许 {@code http://} 或内网/回环地址。
 *
 * <p>命中任何一条即抛 {@link ErrorCode#E_SYS_02}（400）；日志只记拦截原因，**不回显用户输入的地址**，
 * 避免把校验器本身变成内网探测工具。
 */
@Component
public class BaseUrlSecurityValidator {

    private static final Logger log = LoggerFactory.getLogger(BaseUrlSecurityValidator.class);

    /** 内部服务端口（`04` §4.2.2）：非白名单时一律拦截。 */
    private static final Set<Integer> INTERNAL_PORTS = Set.of(3306, 6379, 5672, 15672, 8000, 8080);
    /** 公网允许的端口（`04` §4.2.2：https 仅 443 或 8443）。 */
    private static final Set<Integer> PUBLIC_PORTS = Set.of(443, 8443);
    /** 容器名与内部主机名（`04` §4.2.2）。 */
    private static final Set<String> INTERNAL_HOSTS = Set.of(
            "mysql", "redis", "rabbitmq", "chromadb", "sc-java-backend", "sc-python-ai",
            "sc-mysql", "sc-redis", "sc-rabbitmq", "sc-chromadb", "sc-napcat-onebot");
    private static final Pattern IPV4 = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");

    private final boolean allowLocalModelBaseUrl;
    private final Set<String> allowedOrigins;

    public BaseUrlSecurityValidator(
            @Value("${sc.model.allow-local-base-url:false}") boolean allowLocalModelBaseUrl,
            @Value("${sc.model.allowed-origins:}") String allowedOrigins) {
        this.allowLocalModelBaseUrl = allowLocalModelBaseUrl;
        this.allowedOrigins = Arrays.stream(allowedOrigins == null ? new String[0] : allowedOrigins.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .map(item -> item.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    /** 校验通过即返回；不通过抛 {@link ErrorCode#E_SYS_02}。 */
    public void validate(String baseUrl) {
        String candidate = baseUrl == null ? "" : baseUrl.trim();
        if (candidate.isEmpty() || candidate.length() > 512) {
            throw reject("地址为空或过长");
        }
        if (candidate.contains("\\") || candidate.contains("..")) {
            throw reject("含反斜杠或路径穿越片段");
        }

        URI uri;
        try {
            uri = new URI(candidate);
        } catch (URISyntaxException e) {
            throw reject("不是合法 URI");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw reject("协议不受支持");
        }
        if (uri.getUserInfo() != null) {
            throw reject("URL 内含凭据");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw reject("缺少主机名");
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        String origin = scheme + "://" + normalizedHost + (port > 0 ? ":" + port : "");
        boolean whitelisted = allowLocalModelBaseUrl && allowedOrigins.contains(origin);

        if (whitelisted) {
            log.debug("Base URL 命中本地模型白名单");
            return;
        }
        if (INTERNAL_HOSTS.contains(normalizedHost)) {
            throw reject("指向容器内部主机名");
        }
        if (!"https".equals(scheme)) {
            throw reject("公网地址必须使用 https");
        }
        if (port != -1 && !PUBLIC_PORTS.contains(port)) {
            throw reject("端口不在允许范围（公网仅 443/8443）");
        }
        if (INTERNAL_PORTS.contains(port)) {
            throw reject("端口属于内部服务");
        }
        if (isInternalLiteral(normalizedHost)) {
            throw reject("指向内网、回环或云元数据地址");
        }
        log.debug("Base URL 校验通过（地址本身不写日志）");
    }

    /**
     * 内网/回环/链路本地/云元数据地址的字面量判定。
     *
     * <p>只判字面量与明显的内网主机名；域名解析后的 IP 校验由 Python 侧完成（`04` §4.2.2 规则 2），
     * 这里刻意不做 DNS 解析——否则校验器自己就成了"内网 DNS 探测"的入口。
     */
    static boolean isInternalLiteral(String host) {
        if ("localhost".equals(host) || host.endsWith(".localhost") || host.endsWith(".internal")) {
            return true;
        }
        if ("169.254.169.254".equals(host)) {
            return true;
        }
        if (host.contains(":")) {
            // IPv6 字面量（含 ::1）：公网 IPv6 场景 MVP 未承诺，按不安全处理更稳
            return true;
        }
        Matcher matcher = IPV4.matcher(host);
        if (!matcher.matches()) {
            return false;
        }
        int a = Integer.parseInt(matcher.group(1));
        int b = Integer.parseInt(matcher.group(2));
        return a == 0 || a == 10 || a == 127
                || (a == 169 && b == 254)
                || (a == 172 && b >= 16 && b <= 31)
                || (a == 192 && b == 168)
                || (a == 100 && b >= 64 && b <= 127);
    }

    private static BusinessException reject(String reason) {
        log.warn("Base URL 被安全校验拦截 reason={}", reason);
        return new BusinessException(ErrorCode.E_SYS_02,
                "该自定义 Base URL 未通过安全校验，请更换为公网可访问的模型地址。");
    }
}
