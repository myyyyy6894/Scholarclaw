package com.scholarclaw.common.security;

import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
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
 * <p><b>两层语义（与 Python {@code ssrf_guard} 完全一致）：</b>
 * <ol>
 *   <li><b>硬拒绝</b>：内部服务名、内部端口、云元数据地址（含 IPv6 链路本地与
 *       {@code fd00:ec2::254}）<b>任何配置都不可绕过</b>——即使管理员把它们写进
 *       {@code SC_LOCAL_MODEL_ALLOWED_ORIGINS}。</li>
 *   <li><b>白名单例外</b>：只允许绕过“本地/私有地址”与“公网必须 https”这两条限制，
 *       用于本地 Ollama 等受控场景。</li>
 * </ol>
 *
 * <p><b>分工（`04` §4.2.2 规则 1/2）：</b>本类在 Java 侧做**字面与主机名层面**的校验；
 * Python 在真正发起出站请求前会**再做一次 DNS 解析后的 IP 校验**——域名可能在两次校验之间
 * 指向内网（DNS rebinding），只信一次解析结果的做法会漏。
 *
 * <p>命中任何一条即抛 {@link ErrorCode#E_SYS_02}（400）；日志只记拦截原因，**不回显用户输入的地址**，
 * 避免把校验器本身变成内网探测工具。
 */
@Component
public class BaseUrlSecurityValidator {

    private static final Logger log = LoggerFactory.getLogger(BaseUrlSecurityValidator.class);

    /** 内部服务端口（`04` §4.2.2）：**硬拒绝**，白名单也不放行。 */
    private static final Set<Integer> INTERNAL_PORTS = Set.of(3306, 6379, 5672, 15672, 8000, 8080);
    /** 公网允许的端口（`04` §4.2.2：https 仅 443 或 8443）。 */
    private static final Set<Integer> PUBLIC_PORTS = Set.of(443, 8443);
    /** 容器名与内部主机名（`04` §4.2.2）：**硬拒绝**。 */
    private static final Set<String> INTERNAL_HOSTS = Set.of(
            "mysql", "redis", "rabbitmq", "chromadb", "sc-java-backend", "sc-python-ai",
            "sc-mysql", "sc-redis", "sc-rabbitmq", "sc-chromadb", "sc-napcat-onebot");
    /** 云元数据主机名（**硬拒绝**）。 */
    private static final Set<String> METADATA_HOSTS = Set.of(
            "metadata.google.internal", "metadata.tencentyun.com");
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
        String rawHost = uri.getHost();
        if (rawHost == null || rawHost.isBlank()) {
            throw reject("缺少主机名");
        }
        String originHost = rawHost.toLowerCase(Locale.ROOT);
        String checkHost = stripBrackets(originHost);
        if (checkHost.endsWith(".")) {
            checkHost = checkHost.substring(0, checkHost.length() - 1);
        }
        int port = uri.getPort();

        // ---------- 第一层：硬拒绝（任何配置都不可绕过） ----------
        if (INTERNAL_HOSTS.contains(checkHost) || METADATA_HOSTS.contains(checkHost)) {
            throw reject("指向内部服务或云元数据主机");
        }
        if (port > 0 && INTERNAL_PORTS.contains(port)) {
            throw reject("端口属于内部服务");
        }
        if (isMetadataLiteral(checkHost)) {
            throw reject("指向云元数据、链路本地或回环地址");
        }

        // ---------- 第二层：白名单只豁免"本地/私有地址 + http" ----------
        String origin = scheme + "://" + originHost + (port > 0 ? ":" + port : "");
        if (allowLocalModelBaseUrl && allowedOrigins.contains(origin)) {
            log.debug("Base URL 命中本地模型白名单（硬拒绝项已先行排除）");
            return;
        }
        if (!"https".equals(scheme)) {
            throw reject("公网地址必须使用 https");
        }
        if (port != -1 && !PUBLIC_PORTS.contains(port)) {
            throw reject("端口不在允许范围（公网仅 443/8443）");
        }
        if (isInternalLiteral(checkHost)) {
            throw reject("指向内网、回环或 IPv6 地址");
        }
        log.debug("Base URL 校验通过（地址本身不写日志）");
    }

    /** 去掉 IPv6 字面量的方括号（{@code URI.getHost()} 会保留它们）。 */
    private static String stripBrackets(String host) {
        if (host.startsWith("[") && host.endsWith("]") && host.length() > 2) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    /**
     * 云元数据 / 链路本地地址的**硬拒绝**判定。
     *
     * <p>IPv4 链路本地（{@code 169.254.0.0/16}）整体拒绝：它不可能是合法的模型服务地址，
     * 其中 {@code 169.254.169.254} 是 AWS/GCP/Azure 等云厂商的元数据端点。
     * IPv6 额外拒绝 {@code fd00:ec2::254} 与 {@code fe80::/10}。
     * **回环地址不在此硬拒绝**——本地 Ollama 白名单需要能显式放行回环。
     */
    static boolean isMetadataLiteral(String host) {
        if ("169.254.169.254".equals(host) || "100.100.100.200".equals(host)
                || "fd00:ec2::254".equalsIgnoreCase(host)) {
            return true;
        }
        Matcher matcher = IPV4.matcher(host);
        if (matcher.matches()) {
            int a = Integer.parseInt(matcher.group(1));
            int b = Integer.parseInt(matcher.group(2));
            return a == 169 && b == 254;
        }
        if (host.contains(":")) {
            // 只对 IPv6 字面量调用；含冒号的输入不会触发 DNS
            try {
                InetAddress address = InetAddress.getByName(host);
                return address.isLinkLocalAddress();
            } catch (UnknownHostException e) {
                return false;
            }
        }
        return false;
    }

    /**
     * 内网/回环/私有地址的字面量判定。
     *
     * <p>只判字面量与明显的内网主机名；域名解析后的 IP 校验由 Python 侧完成（`04` §4.2.2 规则 2），
     * 这里刻意不做 DNS 解析——否则校验器自己就成了"内网 DNS 探测"的入口。
     */
    static boolean isInternalLiteral(String host) {
        if ("localhost".equals(host) || host.endsWith(".localhost") || host.endsWith(".internal")) {
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
