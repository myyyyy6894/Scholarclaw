package com.scholarclaw.common.filter;

import java.security.SecureRandom;
import java.util.regex.Pattern;
import org.slf4j.MDC;

/**
 * 全链路追踪上下文（{@code 02} §11、{@code 04} §2.7.1）。
 *
 * <p>{@code trace_id} 在 Java 入口（HTTP 或定时任务）生成，经 {@code X-Trace-Id} 头与
 * RabbitMQ 消息体透传给 Python，并原样带回；两侧日志统一输出
 * {@code [trace_id={}] [user_id={}]}。
 */
public final class TraceContext {

    /** 对外与内部透传的追踪号请求头/响应头名称。 */
    public static final String HEADER_NAME = "X-Trace-Id";

    /** Logback 中引用的 MDC 键名，必须与 {@code logback-spring.xml} 的 pattern 一致。 */
    public static final String MDC_TRACE_ID = "trace_id";
    public static final String MDC_USER_ID = "user_id";

    /**
     * 请求属性名：由后续的认证过滤器（TASK-202）在验签成功后写入当前用户 ID，
     * 追踪过滤器据此把 {@code user_id} 写入 MDC，避免追踪层反向依赖鉴权层。
     */
    public static final String REQUEST_ATTRIBUTE_USER_ID = "sc.userId";

    private static final String HTTP_PREFIX = "tr_";
    private static final String CRON_PREFIX = "tr_cron_";

    /** {@code 04} §2.7.1 规定的入站追踪号格式。 */
    private static final Pattern ACCEPTABLE_INBOUND_TRACE_ID = Pattern.compile("^tr_[0-9a-f]{12}$");

    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final int RANDOM_BYTE_LENGTH = 6;

    private TraceContext() {
    }

    /** 生成 HTTP 入口追踪号：{@code tr_} + 12 位十六进制。 */
    public static String generate() {
        return HTTP_PREFIX + randomHex12();
    }

    /** 生成定时任务入口追踪号（{@code 02} §11 规定为 {@code tr_cron_*}）。 */
    public static String generateCronTraceId() {
        return CRON_PREFIX + randomHex12();
    }

    /** 客户端传入的追踪号是否可接受：只接受文档规定的格式，避免脏值污染日志与调用链。 */
    public static boolean isAcceptableInboundTraceId(String value) {
        return value != null && ACCEPTABLE_INBOUND_TRACE_ID.matcher(value).matches();
    }

    /** 当前线程绑定的追踪号，未绑定返回 {@code null}。 */
    public static String currentTraceId() {
        return MDC.get(MDC_TRACE_ID);
    }

    /**
     * 构造对外响应时使用的追踪号：优先当前线程绑定的值；若当前不在任何入口上下文内
     * （例如直接从定时任务或单元测试构造响应），则按 {@code 02} §11 生成 {@code tr_cron_*}，
     * 保证 {@code Result.trace_id} 永不为空且可区分来源。
     */
    public static String currentOrGeneratedTraceId() {
        String current = currentTraceId();
        return current != null ? current : generateCronTraceId();
    }

    /** 绑定追踪上下文；调用方必须在 {@code finally} 中调用 {@link #clear()}。 */
    public static void bind(String traceId, Long userId) {
        MDC.put(MDC_TRACE_ID, traceId);
        if (userId != null) {
            MDC.put(MDC_USER_ID, String.valueOf(userId));
        } else {
            MDC.remove(MDC_USER_ID);
        }
    }

    /** 解绑，防止线程池复用导致追踪号串线。 */
    public static void clear() {
        MDC.remove(MDC_TRACE_ID);
        MDC.remove(MDC_USER_ID);
    }

    private static String randomHex12() {
        byte[] bytes = new byte[RANDOM_BYTE_LENGTH];
        SecureRandomHolder.next(bytes);
        char[] chars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xFF;
            chars[i * 2] = HEX[value >>> 4];
            chars[i * 2 + 1] = HEX[value & 0x0F];
        }
        return new String(chars);
    }

    /** 延迟持有单例 {@link SecureRandom}，避免类加载期开销。 */
    private static final class SecureRandomHolder {

        private static final SecureRandom INSTANCE = new SecureRandom();

        private SecureRandomHolder() {
        }

        static void next(byte[] bytes) {
            INSTANCE.nextBytes(bytes);
        }
    }
}
