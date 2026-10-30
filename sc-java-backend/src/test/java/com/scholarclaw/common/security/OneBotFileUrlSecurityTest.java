package com.scholarclaw.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TASK-803/805 复核：OneBot 文件 URL 的 SSRF 规则、DNS 全量校验与 Origin 白名单。 */
class OneBotFileUrlSecurityTest {

    private static final OneBotFileUrlSecurity.DnsResolver PUBLIC_DNS =
            host -> new InetAddress[]{InetAddress.getByName("93.184.216.34")};

    @Test
    @DisplayName("公网 https + 默认端口：解析成功并返回钉扎地址")
    void acceptsPublicHttps() {
        OneBotFileUrlSecurity security = new OneBotFileUrlSecurity("", PUBLIC_DNS);

        OneBotFileUrlSecurity.ValidatedTarget target =
                security.validate("https://cdn.example.com/file.pdf");

        assertThat(target.port()).isEqualTo(443);
        assertThat(target.https()).isTrue();
        assertThat(target.hostHeader()).isEqualTo("cdn.example.com");
        assertThat(target.addresses()).hasSize(1);
    }

    @Test
    @DisplayName("域名解析出任意私网地址即整体拒绝，防止 DNS 混入内网")
    void rejectsAnyPrivateResolvedAddress() {
        OneBotFileUrlSecurity.DnsResolver mixed = host -> new InetAddress[]{
                InetAddress.getByName("93.184.216.34"),
                InetAddress.getByName("127.0.0.1")};
        OneBotFileUrlSecurity security = new OneBotFileUrlSecurity("", mixed);

        assertThatThrownBy(() -> security.validate("https://evil.example.com/a.pdf"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.E_IM_02));
    }

    @Test
    @DisplayName("公网地址使用非标准端口：拒绝")
    void rejectsNonStandardPortForPublicHost() {
        OneBotFileUrlSecurity security = new OneBotFileUrlSecurity("", PUBLIC_DNS);

        assertThatThrownBy(() -> security.validate("https://cdn.example.com:8444/a.pdf"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("Origin 白名单只放行私网/回环，链路本地与元数据仍硬拒绝")
    void allowlistPermitsLocalButNotMetadata() throws UnknownHostException {
        Map<String, InetAddress[]> dns = new HashMap<>();
        dns.put("files.local", new InetAddress[]{InetAddress.getByName("127.0.0.1")});
        dns.put("meta.local", new InetAddress[]{InetAddress.getByName("169.254.169.254")});
        OneBotFileUrlSecurity security = new OneBotFileUrlSecurity(
                "http://files.local:8080,http://meta.local:8080",
                host -> dns.getOrDefault(host, new InetAddress[0]));

        OneBotFileUrlSecurity.ValidatedTarget allowed =
                security.validate("http://files.local:8080/a.pdf");
        assertThat(allowed.addresses()).hasSize(1);

        assertThatThrownBy(() -> security.validate("http://meta.local:8080/a.pdf"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.E_IM_02));
    }

    @Test
    @DisplayName("拒绝 userinfo、非 http(s) 协议与内网主机名")
    void rejectsUnsafeUrlShapes() {
        OneBotFileUrlSecurity security = new OneBotFileUrlSecurity("", PUBLIC_DNS);

        assertThatThrownBy(() -> security.validate("file:///etc/passwd"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> security.validate("http://user:pass@example.com/a.pdf"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> security.validate("https://localhost/a.pdf"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> security.validate("https://metadata.google.internal/x"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("IPv4-mapped IPv6 元数据地址同样硬拒绝")
    void rejectsMappedMetadataAddress() throws UnknownHostException {
        OneBotFileUrlSecurity.DnsResolver resolver = host ->
                new InetAddress[]{InetAddress.getByName("::ffff:169.254.169.254")};
        OneBotFileUrlSecurity security = new OneBotFileUrlSecurity("", resolver);

        assertThatThrownBy(() -> security.validate("https://mapped.example.com/a.pdf"))
                .isInstanceOf(BusinessException.class);
    }
}
