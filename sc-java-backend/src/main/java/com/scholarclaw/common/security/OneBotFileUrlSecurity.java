package com.scholarclaw.common.security;

import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * OneBot 文件下载地址的 SSRF 防护与 DNS 钉扎目标解析（TASK-803/805 复核修复）。
 *
 * <p><b>为什么需要独立校验：</b>OneBot 事件里的 {@code file.url} 与 {@code get_file}
 * 返回的 {@code file/url} 都由协议端（或攻击者伪造的 Webhook）控制。只检查
 * {@code http/https} 会让 Java 进程访问 {@code 127.0.0.1}、内网段、链路本地与云元数据地址。
 *
 * <p><b>规则：</b>
 * <ol>
 *   <li>只允许 http/https；拒绝 userinfo、fragment、控制字符与超长 URL；</li>
 *   <li>硬拒绝回环/私网/共享地址/链路本地/组播/未指定地址/云元数据地址；</li>
 *   <li>域名必须解析出至少一个地址，且<b>所有</b>解析结果都通过校验（任一危险即拒绝）；</li>
 *   <li>连接时使用这里返回的 {@link InetAddress} 列表直接建连，不再二次解析，避免 DNS rebinding；</li>
 *   <li>仅当 Origin 精确命中 {@code SC_ONEBOT_FILE_ALLOWED_ORIGINS} 时，才允许私网/回环地址
 *       （用于同机 NapCat 的临时文件服务）；链路本地/云元数据地址即使在白名单内也硬拒绝。</li>
 * </ol>
 *
 * <p>本类只做校验与解析，不发起网络请求；日志不回显完整 URL，避免把校验器变成内网探测工具。
 */
@Component
public class OneBotFileUrlSecurity {

    private static final Logger log = LoggerFactory.getLogger(OneBotFileUrlSecurity.class);

    static final int MAX_URL_LENGTH = 2048;
    private static final Set<Integer> PUBLIC_PORTS = Set.of(80, 443, 8443);
    private static final Set<String> HARD_BLOCKED_HOSTS = Set.of(
            "localhost", "metadata.google.internal", "metadata.tencentyun.com");
    private static final String METADATA_IPV4 = "169.254.169.254";
    private static final String TENCENT_METADATA_IPV4 = "100.100.100.200";

    private final Set<String> allowedOrigins;
    private final DnsResolver dnsResolver;

    @Autowired
    public OneBotFileUrlSecurity(
            @Value("${sc.onebot.file.allowed-origins:}") String allowedOrigins) {
        this(allowedOrigins, InetAddress::getAllByName);
    }

    /** 测试可注入确定性 DNS 解析器；生产使用 {@link InetAddress#getAllByName(String)}。 */
    public OneBotFileUrlSecurity(String allowedOrigins, DnsResolver dnsResolver) {
        this.allowedOrigins = Arrays.stream(
                        allowedOrigins == null ? new String[0] : allowedOrigins.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .map(item -> item.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        this.dnsResolver = dnsResolver == null ? InetAddress::getAllByName : dnsResolver;
    }

    /**
     * 校验 URL 并解析出所有可连接的地址。
     *
     * @throws BusinessException {@link ErrorCode#E_IM_02}：任何不安全或不合法情况
     */
    public ValidatedTarget validate(String rawUrl) {
        String candidate = rawUrl == null ? "" : rawUrl.trim();
        if (candidate.isEmpty() || candidate.length() > MAX_URL_LENGTH) {
            throw reject("地址为空或过长");
        }
        rejectControlCharacters(candidate);

        URI uri;
        try {
            uri = new URI(candidate);
        } catch (URISyntaxException e) {
            throw reject("不是合法 URI");
        }
        String scheme = uri.getScheme() == null
                ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw reject("协议不受支持");
        }
        if (uri.getUserInfo() != null) {
            throw reject("URL 内含用户信息");
        }
        if (uri.getFragment() != null) {
            throw reject("URL 含 fragment");
        }
        String rawHost = uri.getHost();
        if (rawHost == null || rawHost.isBlank()) {
            throw reject("缺少主机名");
        }
        String host = stripBrackets(rawHost.toLowerCase(Locale.ROOT));
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (host.isEmpty()) {
            throw reject("缺少主机名");
        }
        if (HARD_BLOCKED_HOSTS.contains(host)
                || host.endsWith(".localhost") || host.endsWith(".internal")) {
            throw reject("指向本地或内部主机名");
        }

        boolean https = "https".equals(scheme);
        int defaultPort = https ? 443 : 80;
        int port = uri.getPort() < 0 ? defaultPort : uri.getPort();
        if (port <= 0 || port > 65535) {
            throw reject("端口非法");
        }
        String origin = buildOrigin(scheme, host, port);
        boolean allowlisted = allowedOrigins.contains(origin);
        if (!allowlisted && !PUBLIC_PORTS.contains(port)) {
            throw reject("端口不在允许范围（公网仅 80/443/8443）");
        }

        List<InetAddress> addresses = resolve(host);
        if (addresses.isEmpty()) {
            throw reject("域名没有可用地址");
        }
        for (InetAddress address : addresses) {
            if (isHardBlocked(address)) {
                throw reject("指向链路本地、组播或云元数据地址");
            }
            if (!allowlisted && isPrivateOrLocal(address)) {
                throw reject("指向回环或私有地址");
            }
        }
        // Host 头使用原始主机名（IPv6 加方括号），连接使用上面钉扎的地址列表。
        String hostHeader = host.contains(":") ? "[" + host + "]" : host;
        return new ValidatedTarget(uri, hostHeader, port, https, List.copyOf(addresses));
    }

    private List<InetAddress> resolve(String host) {
        try {
            InetAddress[] resolved = dnsResolver.resolve(host);
            return resolved == null ? List.of() : new ArrayList<>(Arrays.asList(resolved));
        } catch (UnknownHostException e) {
            throw reject("域名无法解析");
        }
    }

    /** 即使在白名单内也必须拒绝的地址：未指定、链路本地、组播与云元数据。 */
    static boolean isHardBlocked(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLinkLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            String ipv4 = formatIpv4(bytes);
            return METADATA_IPV4.equals(ipv4) || TENCENT_METADATA_IPV4.equals(ipv4)
                    || "0.0.0.0".equals(ipv4);
        }
        if (bytes.length == 16) {
            if (isIpv4Mapped(bytes)) {
                return isHardBlockedFromIpv4(Arrays.copyOfRange(bytes, 12, 16));
            }
            return isMetadataIpv6(bytes);
        }
        return true;
    }

    /** {@code fd00:ec2::254}（AWS IPv6 元数据）。 */
    private static boolean isMetadataIpv6(byte[] bytes) {
        return (bytes[0] & 0xFF) == 0xFD && (bytes[1] & 0xFF) == 0x00
                && (bytes[2] & 0xFF) == 0x0E && (bytes[3] & 0xFF) == 0xC2
                && (bytes[14] & 0xFF) == 0x02 && (bytes[15] & 0xFF) == 0x54;
    }

    /** 回环、私网、共享地址、保留地址与 IPv6 ULA；白名单可显式放行。 */
    static boolean isPrivateOrLocal(InetAddress address) {
        if (address.isLoopbackAddress() || address.isSiteLocalAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int a = bytes[0] & 0xFF;
            int b = bytes[1] & 0xFF;
            int c = bytes[2] & 0xFF;
            return a == 0 || a == 127
                    || (a == 100 && b >= 64 && b <= 127)
                    || (a == 169 && b == 254)
                    || (a == 192 && b == 0 && (c == 0 || c == 2))
                    || (a == 198 && (b == 18 || b == 19))
                    || (a == 198 && b == 51 && c == 100)
                    || (a == 203 && b == 0 && c == 113)
                    || a >= 240;
        }
        if (bytes.length == 16) {
            if (isIpv4Mapped(bytes)) {
                return isPrivateOrLocalBytes(Arrays.copyOfRange(bytes, 12, 16));
            }
            int first = bytes[0] & 0xFF;
            // fc00::/7 唯一本地地址
            return (first & 0xFE) == 0xFC;
        }
        return true;
    }

    private static boolean isHardBlockedFromIpv4(byte[] bytes) {
        int a = bytes[0] & 0xFF;
        int b = bytes[1] & 0xFF;
        if (a == 169 && b == 254) {
            return true;
        }
        String ipv4 = formatIpv4(bytes);
        return METADATA_IPV4.equals(ipv4) || TENCENT_METADATA_IPV4.equals(ipv4)
                || "0.0.0.0".equals(ipv4) || (a >= 224);
    }

    private static boolean isPrivateOrLocalBytes(byte[] bytes) {
        try {
            return isPrivateOrLocal(InetAddress.getByAddress(bytes));
        } catch (UnknownHostException e) {
            return true;
        }
    }

    private static String buildOrigin(String scheme, String host, int port) {
        String hostPart = host.contains(":") ? "[" + host + "]" : host;
        return scheme + "://" + hostPart + ":" + port;
    }

    private static String stripBrackets(String host) {
        if (host.startsWith("[") && host.endsWith("]") && host.length() > 2) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    private static boolean isIpv4Mapped(byte[] bytes) {
        if (bytes.length != 16) {
            return false;
        }
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return (bytes[10] & 0xFF) == 0xFF && (bytes[11] & 0xFF) == 0xFF;
    }

    private static String formatIpv4(byte[] bytes) {
        return (bytes[0] & 0xFF) + "." + (bytes[1] & 0xFF) + "."
                + (bytes[2] & 0xFF) + "." + (bytes[3] & 0xFF);
    }

    private static void rejectControlCharacters(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch < 0x20 || (ch >= 0x7F && ch <= 0x9F)) {
                throw reject("含控制字符");
            }
        }
    }

    private static BusinessException reject(String reason) {
        log.warn("OneBot 文件地址被安全校验拦截 reason={}", reason);
        return new BusinessException(ErrorCode.E_IM_02);
    }

    /** 已校验并钉扎的下载目标。 */
    public record ValidatedTarget(URI uri, String hostHeader, int port, boolean https,
                                  List<InetAddress> addresses) {
    }

    @FunctionalInterface
    public interface DnsResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }
}
