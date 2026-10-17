package com.scholarclaw.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * 日志脱敏断言（{@code NFR-SEC-02}、{@code 04} §2.3.3）。
 */
class SensitiveDataMaskingConverterTest {

    @Test
    @DisplayName("裸的模型 API Key 前缀被遮蔽，上下文文字保留")
    void masksPrefixedApiKeys() {
        String masked = SensitiveDataMaskingConverter.mask("请求头携带 sk-liveabcdef1234567890 已失效");

        assertThat(masked).doesNotContain("sk-liveabcdef1234567890");
        assertThat(masked).contains("sk-****");
    }

    @Test
    @DisplayName("key=value 形式的凭据值整体被遮蔽，非敏感参数不受影响")
    void masksBareKeyValueSecrets() {
        String masked = SensitiveDataMaskingConverter.mask(
                "api_key=abc123def456 password=hunter2 model=gpt-4o trace_id=tr_0123456789ab");

        assertThat(masked)
                .doesNotContain("abc123def456")
                .doesNotContain("hunter2")
                .contains("model=gpt-4o");
    }

    @Test
    @DisplayName("JSON 形式的敏感字段值整体被遮蔽（含空格值）")
    void masksQuotedJsonSecretFields() {
        String masked = SensitiveDataMaskingConverter.mask(
                "{\"api_key\":\"sk-abcdef123456\",\"model\":\"gpt-4o\",\"token\":\"my secret value\"}");

        assertThat(masked).doesNotContain("sk-abcdef123456");
        assertThat(masked).contains("\"api_key\":\"****\"");
        assertThat(masked).contains("\"token\":\"****\"");
        assertThat(masked).contains("\"model\":\"gpt-4o\"");
    }

    @Test
    @DisplayName("JWT 与 Bearer 认证头被遮蔽，请求头名称保留便于排障")
    void masksJwtAndBearerTokens() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMDAwMSJ9.c2lnbmF0dXJl";
        String masked = SensitiveDataMaskingConverter.mask("Authorization: Bearer " + jwt);

        assertThat(masked)
                .doesNotContain(jwt)
                .doesNotContain("c2lnbmF0dXJl")
                .contains("Authorization")
                .contains("****");
    }

    @Test
    @DisplayName("AES-256-GCM 密文同样不得入日志，只保留版本前缀")
    void masksEncryptedSecretCiphertext() {
        String ciphertext = "v1:Q2F1dGhTZWNyZXRJdg==:c2NfY2lwaGVydGV4dF90YWdfdmFsdWU=";
        String masked = SensitiveDataMaskingConverter.mask("保存密文 " + ciphertext);

        assertThat(masked).doesNotContain("Q2F1dGhTZWNyZXRJdg==");
        assertThat(masked).contains("v1:****:****");
    }

    @Test
    @DisplayName("普通业务信息不被误伤")
    void keepsOrdinaryBusinessLogsIntact() {
        String message = "任务创建成功 task_id=102 course_id=2001 parse_version=3";

        assertThat(SensitiveDataMaskingConverter.mask(message)).isEqualTo(message);
    }

    @Test
    @DisplayName("null 与空串原样返回")
    void handlesNullAndEmpty() {
        assertThat(SensitiveDataMaskingConverter.mask(null)).isNull();
        assertThat(SensitiveDataMaskingConverter.mask("")).isEmpty();
    }

    @Test
    @DisplayName("logback-spring.xml 确实挂载了脱敏转换器与 trace/user MDC 字段")
    void logbackConfigurationWiresMaskingAndMdc() throws IOException {
        String logback = new String(new ClassPathResource("logback-spring.xml").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);

        assertThat(logback).contains("com.scholarclaw.common.logging.SensitiveDataMaskingConverter");
        assertThat(logback).contains("%maskedMsg");
        assertThat(logback).contains("%X{trace_id}");
        assertThat(logback).contains("%X{user_id}");
    }
}
