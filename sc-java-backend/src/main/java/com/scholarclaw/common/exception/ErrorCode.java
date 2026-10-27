package com.scholarclaw.common.exception;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * 全局统一错误码字典（{@code 04_API接口文档} 第 7 章）。
 *
 * <p>铁律：同一错误在任何接口都必须返回同一 {@code code}，禁止新增第二套命名或同码不同义。
 * 成功码不在此枚举内，见 {@link com.scholarclaw.common.response.Result#SUCCESS_CODE}。
 *
 * <p>{@code httpStatus} 允许为 {@code null}，表示该码没有 HTTP 响应状态映射，只有两种情形：
 * <ul>
 *   <li>异步文档错误码（{@code E-RAG-01} / {@code E-RAG-02}）：写入
 *       {@code t_knowledge_doc.error_code} 供列表展示，不经由同步 REST 响应返回；</li>
 *   <li>IM 渠道错误码（{@code E-IM-01~04}，定义见 {@code 01} §FR-IM-001）：入站静默丢弃或
 *       QQ 侧回复文案，同样不映射 HTTP 状态；{@code E-RAG-03} 为 SSE {@code event: error}，
 *       响应本身已是流式 200。</li>
 * </ul>
 * 若这些码意外经由 REST 异常处理器上抛，按 {@code 500 INTERNAL_ERROR} 兜底（见
 * {@link GlobalExceptionHandler}）。
 */
public enum ErrorCode {

    // ---------- 400 Bad Request：参数校验与业务前置拦截（04 §2.3.2 第 1、2 类）----------
    VALIDATION_ERROR("VALIDATION_ERROR", HttpStatus.BAD_REQUEST, null,
            "请求参数校验失败。"),
    IDEMPOTENCY_KEY_REQUIRED("IDEMPOTENCY_KEY_REQUIRED", HttpStatus.BAD_REQUEST, null,
            "缺少必填的 client_request_id，无法保证本次请求的幂等语义。"),
    INVALID_CALENDAR_DATE("INVALID_CALENDAR_DATE", HttpStatus.BAD_REQUEST, null,
            "传入的公历日期不存在或时间组合不合法，请检查后重选。"),
    E_SYS_01("E-SYS-01", HttpStatus.BAD_REQUEST, null,
            "⚠️ 尚未配置完整的模型，请先前往 Web 工作台【个人设置 -> 模型配置】完成配置。"),
    E_SYS_02("E-SYS-02", HttpStatus.BAD_REQUEST, null,
            "该自定义 Base URL 未通过安全校验（协议/端口/内网地址/重定向限制），请更换地址或联系管理员配置本地模型白名单。"),
    FILE_SIZE_EXCEEDED("FILE_SIZE_EXCEEDED", HttpStatus.BAD_REQUEST, null,
            "上传文件为空或超过 50MB 上限，请压缩或拆分后重试。"),
    FILE_TYPE_UNSUPPORTED("FILE_TYPE_UNSUPPORTED", HttpStatus.BAD_REQUEST, null,
            "当前版本仅支持 .pdf、.md、.txt 格式的课件。"),
    FILE_MAGIC_INVALID("FILE_MAGIC_INVALID", HttpStatus.BAD_REQUEST, null,
            "文件内容与扩展名不匹配，文件可能已损坏或后缀被伪造。"),
    COURSE_NO_READY_DOCS("COURSE_NO_READY_DOCS", HttpStatus.BAD_REQUEST, null,
            "该课程下暂无已就绪的课件，请先上传课件或等待解析完成。"),

    // ---------- 401 Unauthorized：认证失败（04 §2.3.2 第 3 类）----------
    AUTH_UNAUTHORIZED("AUTH_UNAUTHORIZED", HttpStatus.UNAUTHORIZED, null,
            "登录状态无效或已过期，请重新登录。"),
    AUTH_INVALID_CREDENTIALS("AUTH_INVALID_CREDENTIALS", HttpStatus.UNAUTHORIZED, null,
            "用户名或密码错误。"),
    AUTH_TOKEN_REVOKED("AUTH_TOKEN_REVOKED", HttpStatus.UNAUTHORIZED, null,
            "当前登录凭证已注销，请重新登录。"),
    ACCOUNT_DEACTIVATED("ACCOUNT_DEACTIVATED", HttpStatus.UNAUTHORIZED, null,
            "该账号已注销，无法继续使用。"),

    // ---------- 403 Forbidden：内部接口鉴权（04 §2.3.2 第 4 类）----------
    AUTH_FORBIDDEN("AUTH_FORBIDDEN", HttpStatus.FORBIDDEN, null,
            "无权访问该资源。"),

    // ---------- 404 Not Found：资源不存在与防 IDOR（04 §2.3.2 第 4、5 类）----------
    RESOURCE_NOT_FOUND("RESOURCE_NOT_FOUND", HttpStatus.NOT_FOUND, null,
            "目标资源不存在或已被删除。"),
    DRAFT_EXPIRED_OR_NOT_FOUND("DRAFT_EXPIRED_OR_NOT_FOUND", HttpStatus.NOT_FOUND, null,
            "该变更草案已过期或已被处理，请刷新任务看板。"),

    // ---------- 409 Conflict：唯一约束与状态机冲突（04 §2.3.2 第 5 类）----------
    USER_ALREADY_EXISTS("USER_ALREADY_EXISTS", HttpStatus.CONFLICT, null,
            "该用户名已被占用，请更换其他用户名。"),
    IM_ACCOUNT_ALREADY_BOUND("IM_ACCOUNT_ALREADY_BOUND", HttpStatus.CONFLICT, null,
            "该 IM 账号已被其他账号绑定。"),
    COURSE_NAME_DUPLICATE("COURSE_NAME_DUPLICATE", HttpStatus.CONFLICT, null,
            "已存在同名的活动课程空间，请更换课程名称。"),
    DOC_DUPLICATE_SHA256("DOC_DUPLICATE_SHA256", HttpStatus.CONFLICT, null,
            "该课件已存在于当前课程知识库中，无需重复上传。"),
    E_SYS_03("E-SYS-03", HttpStatus.CONFLICT, null,
            "当前课程索引正在重建，重建完成前无法执行该操作。"),
    COURSE_INDEX_REBUILDING("COURSE_INDEX_REBUILDING", HttpStatus.CONFLICT, null,
            "⏳ 当前课程正在重建向量索引，请稍后再试。"),
    STATE_CONFLICT("STATE_CONFLICT", HttpStatus.CONFLICT, null,
            "当前资源状态不允许执行该操作，请刷新后重试。"),
    REQUEST_IN_PROGRESS("REQUEST_IN_PROGRESS", HttpStatus.CONFLICT, null,
            "相同请求正在处理中，请勿重复提交。"),
    IDEMPOTENCY_CONFLICT("IDEMPOTENCY_CONFLICT", HttpStatus.CONFLICT, null,
            "同一 client_request_id 已用于不同的请求内容，请生成新的请求 ID 后重试。"),

    // ---------- 非 HTTP 映射：异步文档解析与 IM 渠道 ----------
    E_RAG_01("E-RAG-01", null, "SCAN_PDF_OCR_NOT_ENABLED",
            "扫描件暂不支持，请上传文字版 PDF（OCR 将在 v1.1 开放）。"),
    E_RAG_02("E-RAG-02", null, "INGEST_RETRY_EXHAUSTED",
            "课件解析多次重试后仍失败，请稍后手动重试或更换文件。"),
    E_RAG_03("E-RAG-03", null, null,
            "知识库上下文读取暂时不可用，请稍后重试。"),
    E_IM_01("E-IM-01", null, null,
            "非白名单用户消息，已静默丢弃。"),
    E_IM_02("E-IM-02", null, null,
            "❌ 从聊天端拉取文件失败（可能因平台链接过期或群文件权限限制）。请重新转发，或直接前往 PC Web 工作台上传该课件。"),
    E_IM_03("E-IM-03", null, null,
            "⚠️ 未能完整读取合并转发内的嵌套内容，请将关键通知文字直接复制发送给我，或在 Web 工作台粘贴提取。"),
    E_IM_04("E-IM-04", null, null,
            "🤔 收到你的消息，请确认需要执行哪项操作？回复【1】提取为 DDL 待办，回复【2】检索课程知识库回答，回复【3】忽略。"),

    // ---------- 5xx：下游与系统内部异常（04 §2.3.2 第 6 类）----------
    FEATURE_NOT_AVAILABLE("FEATURE_NOT_AVAILABLE", HttpStatus.NOT_IMPLEMENTED, null,
            "该能力尚未开放，待异步解析与重建任务接入后可用。"),
    UPSTREAM_MODEL_AUTH_ERROR("UPSTREAM_MODEL_AUTH_ERROR", HttpStatus.BAD_GATEWAY, null,
            "模型服务鉴权失败，请检查【BYOK 设置】中的 API Key 或模型名称是否正确。"),
    UPSTREAM_MODEL_RATE_LIMITED("UPSTREAM_MODEL_RATE_LIMITED", HttpStatus.BAD_GATEWAY, null,
            "上游模型触发速率或配额限制，请稍后重试。"),
    UPSTREAM_MODEL_ERROR("UPSTREAM_MODEL_ERROR", HttpStatus.BAD_GATEWAY, null,
            "上游模型服务暂时异常，请稍后重试。"),
    UPSTREAM_TIMEOUT("UPSTREAM_TIMEOUT", HttpStatus.GATEWAY_TIMEOUT, null,
            "响应超时，可使用原请求标识重试。"),
    INTERNAL_ERROR("INTERNAL_ERROR", HttpStatus.INTERNAL_SERVER_ERROR, null,
            "系统繁忙，请稍后重试。");

    private static final Map<String, ErrorCode> BY_CODE;

    static {
        Map<String, ErrorCode> byCode = new LinkedHashMap<>();
        for (ErrorCode value : values()) {
            byCode.put(value.code, value);
        }
        BY_CODE = Collections.unmodifiableMap(byCode);
    }

    private final String code;
    private final HttpStatus httpStatus;
    private final String alias;
    private final String defaultMessage;

    ErrorCode(String code, HttpStatus httpStatus, String alias, String defaultMessage) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.alias = alias;
        this.defaultMessage = defaultMessage;
    }

    /** 对外 JSON 中的 {@code code} 原样取值（如 {@code "E-SYS-02"}）。 */
    public String code() {
        return code;
    }

    /** HTTP 响应状态；{@code null} 表示该码无 HTTP 映射，含义见枚举类注释。 */
    public HttpStatus httpStatus() {
        return httpStatus;
    }

    /** 文档中与 {@link #code} 等价的别称（如 {@code SCAN_PDF_OCR_NOT_ENABLED}），无则 {@code null}。 */
    public String alias() {
        return alias;
    }

    /** 脱敏后的默认用户可读信息；不得包含堆栈、SQL、内部主机名或明文凭据。 */
    public String defaultMessage() {
        return defaultMessage;
    }

    /** 按 {@code code} 或 {@code alias} 反查；用于把 {@code t_knowledge_doc.error_code} 等字符串还原为枚举。 */
    public static ErrorCode fromCode(String codeOrAlias) {
        if (codeOrAlias == null) {
            return null;
        }
        ErrorCode direct = BY_CODE.get(codeOrAlias);
        if (direct != null) {
            return direct;
        }
        for (ErrorCode value : values()) {
            if (codeOrAlias.equals(value.alias)) {
                return value;
            }
        }
        return null;
    }
}
