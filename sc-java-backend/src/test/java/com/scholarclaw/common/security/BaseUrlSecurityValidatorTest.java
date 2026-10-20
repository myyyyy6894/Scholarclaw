package com.scholarclaw.common.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 自定义 Base URL 的第一道 SSRF 校验（{@code NFR-SEC-05}、{@code 04} §4.2.2 规则 1）。
 *
 * <p>覆盖文档点名的攻击面：云元数据、内网段、容器主机名、内部端口、协议降级、凭据注入、
 * 路径穿越与未授权的本地 Ollama。DNS 解析后的二次校验由 Python 侧负责，见
 * {@code sc-python-ai/tests/test_ssrf_guard.py}。
 */
class BaseUrlSecurityValidatorTest {

    private static final BaseUrlSecurityValidator STRICT = new BaseUrlSecurityValidator(false, "");
    private static final BaseUrlSecurityValidator WITH_LOCAL_WHITELIST = new BaseUrlSecurityValidator(
            true, "http://127.0.0.1:11434, http://192.168.1.20:11434");
    /**
     * 被管理员误配的“毒化白名单”：即使写进白名单，内部服务名/端口与元数据地址也必须硬拒绝。
     */
    private static final BaseUrlSecurityValidator POISONED_WHITELIST = new BaseUrlSecurityValidator(
            true,
            "http://sc-mysql:3306, http://127.0.0.1:3306, http://100.100.100.200, "
                    + "http://[fd00:ec2::254], http://[fe80::1], "
                    + "http://127.0.0.1:11434, http://[::1]:11434");

    @Test
    @DisplayName("公网 https 443 / 8443 放行")
    void acceptsPublicHttps() {
        assertThatCode(() -> STRICT.validate("https://api.deepseek.com/v1")).doesNotThrowAnyException();
        assertThatCode(() -> STRICT.validate("https://api.siliconflow.cn:8443/v1")).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "拒绝 {0}")
    @ValueSource(strings = {
            // 协议降级与非 http(s)
            "http://api.deepseek.com/v1",
            "file:///etc/passwd",
            "gopher://127.0.0.1:6379/_INFO",
            "ftp://example.com",
            // 凭据注入与路径穿越
            "https://user:pass@api.deepseek.com/v1",
            "https://api.deepseek.com/v1/..%2f..%2fadmin",
            "https://api.deepseek.com\\@169.254.169.254/",
            // 控制字符（C0 / DEL / C1）与编码后的路径穿越
            "https://api.deepseek.com/v1/\u0000admin",
            "https://api.deepseek.com/v1/\u001fadmin",
            "https://api.deepseek.com/v1/\u007fadmin",
            "https://api.deepseek.com/v1/\u0085admin",
            "https://api.deepseek.com/v1/%2e%2e/admin",
            "https://api.deepseek.com/v1/%2E%2E/admin",
            "https://api.deepseek.com/v1/%252e%252e/admin",
            "https://api.deepseek.com/v1/%5cadmin",
            "https://api.deepseek.com/v1/%00admin",
            // 内网端口
            "https://example.com:3306/",
            "https://example.com:6379/",
            "https://example.com:8000/",
            // 容器主机名
            "https://sc-mysql:443/",
            "https://redis:8443/",
            "https://sc-python-ai/",
            // 云元数据 / 回环 / 内网字面量
            "https://169.254.169.254/latest/meta-data",
            "https://100.100.100.200/latest/meta-data",
            "https://[fd00:ec2::254]/",
            "https://[fe80::1]/",
            "https://127.0.0.1:443/",
            "https://10.0.0.8:443/",
            "https://172.16.0.5:443/",
            "https://192.168.1.5:443/",
            "https://localhost:443/",
            "https://[::1]:443/",
            // 公网不允许的端口
            "https://example.com:8081/"
    })
    void rejectsDeniedTargets(String baseUrl) {
        assertThatThrownBy(() -> STRICT.validate(baseUrl))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> org.assertj.core.api.Assertions
                        .assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.E_SYS_02))
                .hasMessageNotContaining("127.0.0.1")
                .hasMessageNotContaining("169.254");
    }

    @Test
    @DisplayName("本地 Ollama 必须同时开启开关并精确命中 origin 白名单")
    void localWhitelistRequiresExactOriginAndSwitch() {
        // 开关关闭：即使已经配了白名单也不放行
        assertThatThrownBy(() -> STRICT.validate("http://127.0.0.1:11434"))
                .isInstanceOf(BusinessException.class);

        assertThatCode(() -> WITH_LOCAL_WHITELIST.validate("http://127.0.0.1:11434"))
                .doesNotThrowAnyException();
        assertThatCode(() -> WITH_LOCAL_WHITELIST.validate("http://192.168.1.20:11434/v1"))
                .doesNotThrowAnyException();

        // 端口不同即不是"精确匹配"
        assertThatThrownBy(() -> WITH_LOCAL_WHITELIST.validate("http://127.0.0.1:11435"))
                .isInstanceOf(BusinessException.class);
        // 主机不同同理
        assertThatThrownBy(() -> WITH_LOCAL_WHITELIST.validate("http://127.0.0.2:11434"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("毒化白名单不能放行内部服务名、内部端口与云元数据地址（硬拒绝优先）")
    void whitelistNeverBypassesHardDeniedTargets() {
        for (String poisoned : new String[]{
                "http://sc-mysql:3306",
                "http://127.0.0.1:3306",
                "http://100.100.100.200",
                "http://[fd00:ec2::254]",
                "http://[fe80::1]",
        }) {
            assertThatThrownBy(() -> POISONED_WHITELIST.validate(poisoned))
                    .as("白名单中的 %s 必须仍被硬拒绝", poisoned)
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> org.assertj.core.api.Assertions
                            .assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ErrorCode.E_SYS_02));
        }

        // 同一份白名单里的合法 Ollama 地址仍然放行
        assertThatCode(() -> POISONED_WHITELIST.validate("http://127.0.0.1:11434"))
                .doesNotThrowAnyException();
        assertThatCode(() -> POISONED_WHITELIST.validate("http://[::1]:11434"))
                .as("回环 IPv6 不是元数据硬拒绝项，白名单可显式放行")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("合法点号不被路径穿越规则误拦")
    void acceptsLegalDots() {
        assertThatCode(() -> STRICT.validate("https://api.deepseek.com/v1.0/models"))
                .doesNotThrowAnyException();
        assertThatCode(() -> STRICT.validate("https://api.deepseek.com/a.b/c"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("非法 URI、空值与超长地址一律拒绝")
    void rejectsMalformedInput() {
        assertThatThrownBy(() -> STRICT.validate(null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> STRICT.validate("")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> STRICT.validate("not a url")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> STRICT.validate("https://" + "a".repeat(600)))
                .isInstanceOf(BusinessException.class);
    }
}
