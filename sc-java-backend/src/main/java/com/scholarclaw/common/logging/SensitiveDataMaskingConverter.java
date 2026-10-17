package com.scholarclaw.common.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.regex.Pattern;

/**
 * 日志脱敏转换器（{@code NFR-SEC-02}、{@code 04} §2.3.3）。
 *
 * <p>在日志格式化阶段统一处理整条消息，而不是要求每个调用点自觉避开敏感值——后者一旦漏掉一处
 * 就是红线违规。{@code logback-spring.xml} 通过 {@code %maskedMsg} 使用本类。
 *
 * <p>覆盖的敏感形态：
 * <ol>
 *   <li>JWT（{@code eyJ...}）与 AES-256-GCM 密文（{@code v1:{iv}:{ct}}）——密文同样不得入日志；</li>
 *   <li>{@code sk-} / {@code rk-} / {@code pk-} 前缀的模型 API Key；</li>
 *   <li>JSON 形如 {@code "api_key": "..."} 以及裸露 {@code key=value} 形式的
 *       api_key / authorization / password / secret / token；</li>
 *   <li>{@code Bearer <token>} 认证头。</li>
 * </ol>
 *
 * <p>已知边界（不夸大能力）：本转换器是模式匹配，无法识别任意自定义命名的凭据字段；
 * 因此调用点仍必须遵守"不主动打印凭据"的约束，本类是兜底而非免罪符。
 */
public class SensitiveDataMaskingConverter extends MessageConverter {

    private static final String MASK = "****";

    /** 密文格式 v1:{base64_iv}:{base64_ciphertext_with_tag}：整体遮蔽，只保留版本前缀便于排查格式问题。 */
    private static final Pattern ENCRYPTED_SECRET =
            Pattern.compile("\\bv1:[A-Za-z0-9+/=_\\-]+:[A-Za-z0-9+/=_\\-]+");

    /** JWT：header.payload.signature 三段式。 */
    private static final Pattern JWT = Pattern.compile("\\beyJ[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]*");

    /** 常见模型厂商 API Key 前缀。 */
    private static final Pattern PREFIXED_API_KEY = Pattern.compile("\\b(sk|rk|pk)-[A-Za-z0-9_\\-]{6,}");

    /** JSON 双引号形式："api_key": "值" —— 允许值内包含空格。 */
    private static final Pattern QUOTED_SECRET_FIELD = Pattern.compile(
            "(?i)(\"(?:api[_-]?key|apikey|authorization|password|passwd|secret|token|access[_-]?token)\"\\s*:\\s*\")([^\"]*)(\")");

    /** 裸露 key=value / key: value 形式。 */
    private static final Pattern BARE_SECRET_FIELD = Pattern.compile(
            "(?i)\\b(api[_-]?key|apikey|authorization|password|passwd|secret|token)\\b(\\s*[:=]\\s*)(\"?)([^\\s,;\"}]+)");

    /** Authorization: Bearer <token>。 */
    private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)\\b(bearer)(\\s+)([A-Za-z0-9._\\-]{6,})");

    @Override
    public String convert(ILoggingEvent event) {
        return mask(super.convert(event));
    }

    /** 对单条日志消息做脱敏；{@code null} 原样返回。 */
    public static String mask(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        String masked = ENCRYPTED_SECRET.matcher(raw).replaceAll("v1:" + MASK + ":" + MASK);
        masked = JWT.matcher(masked).replaceAll(MASK);
        masked = PREFIXED_API_KEY.matcher(masked).replaceAll("$1-" + MASK);
        masked = QUOTED_SECRET_FIELD.matcher(masked).replaceAll("$1" + MASK + "$3");
        masked = BARE_SECRET_FIELD.matcher(masked).replaceAll("$1$2$3" + MASK);
        masked = BEARER_TOKEN.matcher(masked).replaceAll("$1$2" + MASK);
        return masked;
    }
}
