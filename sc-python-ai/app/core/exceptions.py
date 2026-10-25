"""异常契约与统一异常映射（`02` §5.3、`04` §2.3.2 / 第 7 章）。

两条硬约束：

1. **错误码只能取 `04` 第 7 章的稳定码**，禁止另造。这里只登记本服务确实会产生的子集。
2. **对外消息必须脱敏**：异常消息不得包含密钥、连接串、内部主机名或堆栈。堆栈只进日志，
   且日志经 `app.core.logging` 的掩码过滤器处理。

分类依据 `02` §5.3：`RetryableAiError` 表示可安全重试的瞬态失败（上游限流/5xx），
`NonRetryableAiError` 表示重试无意义（鉴权失败、参数非法、重定向被拒、扫描件不支持）。
"""

from __future__ import annotations

from enum import StrEnum
from typing import Any

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from starlette.exceptions import HTTPException as StarletteHTTPException
from starlette.responses import JSONResponse

from app.core import tracing
from app.core.logging import get_logger
from app.schemas.common import InternalResponse

logger = get_logger(__name__)


class AiErrorCode(StrEnum):
    """本服务会对外产生的错误码子集（取值与 `04` 第 7 章、Java 侧 `ErrorCode` 完全一致）。"""

    VALIDATION_ERROR = "VALIDATION_ERROR"
    AUTH_FORBIDDEN = "AUTH_FORBIDDEN"
    RESOURCE_NOT_FOUND = "RESOURCE_NOT_FOUND"
    FEATURE_NOT_AVAILABLE = "FEATURE_NOT_AVAILABLE"
    E_SYS_02 = "E-SYS-02"
    E_RAG_01 = "E-RAG-01"
    E_RAG_02 = "E-RAG-02"
    E_RAG_03 = "E-RAG-03"
    UPSTREAM_MODEL_AUTH_ERROR = "UPSTREAM_MODEL_AUTH_ERROR"
    UPSTREAM_MODEL_RATE_LIMITED = "UPSTREAM_MODEL_RATE_LIMITED"
    UPSTREAM_MODEL_ERROR = "UPSTREAM_MODEL_ERROR"
    UPSTREAM_TIMEOUT = "UPSTREAM_TIMEOUT"
    INTERNAL_ERROR = "INTERNAL_ERROR"


#: 错误码 -> HTTP 状态。`None` 表示该码无 HTTP 映射（异步文档错误码 / SSE 错误帧）。
ERROR_CODE_HTTP_STATUS: dict[AiErrorCode, int | None] = {
    AiErrorCode.VALIDATION_ERROR: 400,
    AiErrorCode.AUTH_FORBIDDEN: 403,
    AiErrorCode.RESOURCE_NOT_FOUND: 404,
    AiErrorCode.FEATURE_NOT_AVAILABLE: 501,
    AiErrorCode.E_SYS_02: 400,
    AiErrorCode.E_RAG_01: None,
    AiErrorCode.E_RAG_02: None,
    AiErrorCode.E_RAG_03: None,
    AiErrorCode.UPSTREAM_MODEL_AUTH_ERROR: 502,
    AiErrorCode.UPSTREAM_MODEL_RATE_LIMITED: 502,
    AiErrorCode.UPSTREAM_MODEL_ERROR: 502,
    AiErrorCode.UPSTREAM_TIMEOUT: 504,
    AiErrorCode.INTERNAL_ERROR: 500,
}


class AiEngineError(Exception):
    """本服务所有可预期失败的基类。"""

    retryable: bool = False

    def __init__(
        self,
        error_code: AiErrorCode,
        message: str,
        *,
        http_status: int | None = None,
        retryable: bool | None = None,
    ) -> None:
        super().__init__(message)
        self.error_code = error_code
        #: 脱敏后的用户可读消息；调用方必须保证不含密钥/连接串/内部地址。
        self.message = message
        self.http_status = ERROR_CODE_HTTP_STATUS[error_code] if http_status is None else http_status
        if retryable is not None:
            # 同一错误码在不同上游状态下的可重试性可能不同（如 500/501/505 不重试），
            # 用实例属性覆盖类默认值，避免另造错误码。
            self.retryable = retryable

    def as_response(self, *, trace_id: str | None = None) -> InternalResponse[None]:
        return InternalResponse.failure(self.error_code.value, self.message, trace_id=trace_id)


class NonRetryableAiError(AiEngineError):
    """重试无意义的失败。"""

    retryable = False


class RetryableAiError(AiEngineError):
    """可安全重试的瞬态失败，由上层按既定退避策略重试。"""

    retryable = True


# ---------- 具体失败类型：只登记本服务当前会抛出的场景 ----------


class InternalTokenInvalidError(NonRetryableAiError):
    """内部令牌缺失或错误（`04` §2.7.1：缺失或错误一律 403，且不得回显令牌）。"""

    def __init__(self, message: str = "无权访问该资源。") -> None:
        super().__init__(AiErrorCode.AUTH_FORBIDDEN, message)


class BaseUrlRejectedError(NonRetryableAiError):
    """自定义 Base URL 未通过安全校验，或上游返回了重定向（`NFR-SEC-05`）。

    URL/IP 层面的校验由 :mod:`app.services.gateway.ssrf_guard` 在每次出站前执行
    （TASK-203/204）；传输层另外拒绝把 3xx 当作成功，避免重定向绕过校验。
    """

    def __init__(self, message: str = "该自定义 Base URL 未通过安全校验，或上游返回了重定向。") -> None:
        super().__init__(AiErrorCode.E_SYS_02, message)


class ScanPdfOcrNotEnabledError(NonRetryableAiError):
    """扫描版/纯图片 PDF（`02` §5.2.1：每页字符密度 < 20）。落地于 TASK-502。"""

    def __init__(self, message: str = "扫描件暂不支持，请上传文字版 PDF（OCR 将在 v1.1 开放）。") -> None:
        super().__init__(AiErrorCode.E_RAG_01, message)


class IngestRetryExhaustedError(NonRetryableAiError):
    """异步解析重试耗尽（落地于 TASK-505 的 DLQ 处理）。"""

    def __init__(self, message: str = "课件解析多次重试后仍失败，请稍后手动重试或更换文件。") -> None:
        super().__init__(AiErrorCode.E_RAG_02, message)


class ParentChunkFetchFailedError(NonRetryableAiError):
    """回查 Java 父块全文超时或 5xx（`02` §5.2.2：严禁降级用残缺子块拼答案）。"""

    def __init__(self, message: str = "知识库上下文读取暂时不可用，请稍后重试。") -> None:
        super().__init__(AiErrorCode.E_RAG_03, message)


class CitationVerificationFailedError(NonRetryableAiError):
    """引用校验未通过（非法/超长/畸形/无有效引用）。

    复用 `E-RAG-03`：当前错误码字典没有专门的引用校验码，RAG 上下文读取失败已使用该码；
    调用方必须中止回答，不得把半截内容当作成功落库。
    """

    def __init__(self, message: str) -> None:
        super().__init__(AiErrorCode.E_RAG_03, message)


class UpstreamModelAuthError(NonRetryableAiError):
    """上游模型鉴权失败（401/403/404）。"""

    def __init__(self, message: str = "模型服务鉴权失败，请检查【BYOK 设置】中的 API Key 或模型名称是否正确。") -> None:
        super().__init__(AiErrorCode.UPSTREAM_MODEL_AUTH_ERROR, message)


class UpstreamModelRateLimitedError(RetryableAiError):
    """上游模型限流（429，且退避重试后仍受限）。"""

    def __init__(self, message: str = "上游模型触发速率或配额限制，请稍后重试。") -> None:
        super().__init__(AiErrorCode.UPSTREAM_MODEL_RATE_LIMITED, message)


class UpstreamModelError(RetryableAiError):
    """上游模型服务异常（5xx 或响应不兼容）。

    `500/501/505` 等不在 `NFR-REL-01` 允许重试的状态集合内，构造时传
    ``retryable=False``，错误码与脱敏文案保持不变。
    """

    def __init__(
        self, message: str = "上游模型服务暂时异常，请稍后重试。", *, retryable: bool = True
    ) -> None:
        super().__init__(AiErrorCode.UPSTREAM_MODEL_ERROR, message, retryable=retryable)


class UpstreamTimeoutError(NonRetryableAiError):
    """调用外部模型或 Java 内部接口超时。"""

    def __init__(self, message: str = "响应超时，可使用原请求标识重试。") -> None:
        super().__init__(AiErrorCode.UPSTREAM_TIMEOUT, message)


# ---------- 统一异常映射 ----------

#: Starlette 内建异常状态码到文档错误码的映射；未列出的 4xx 归 `VALIDATION_ERROR`，
#: 5xx 归 `INTERNAL_ERROR`（`04` 第 7 章没有"方法不允许"这类码，故按此归并）。
_STARLETTE_STATUS_TO_ERROR_CODE: dict[int, AiErrorCode] = {
    403: AiErrorCode.AUTH_FORBIDDEN,
    404: AiErrorCode.RESOURCE_NOT_FOUND,
}


def register_exception_handlers(app: FastAPI) -> None:
    """把所有异常统一映射为 `InternalResponse<T>` 信封，避免 FastAPI 默认的 `{"detail": ...}`。

    兜底分支只返回脱敏文案 + `trace_id`，绝不把异常原文或堆栈回传调用方。

    **为什么这里要显式回写 `X-Trace-Id`**：未捕获异常由 Starlette 的 `ServerErrorMiddleware`
    兜底，它在用户中间件**之外**，因此追踪中间件的响应头注入会被绕过。若不由异常处理器
    显式补上，500 响应就会出现"响应体里有 `trace_id`、响应头里没有"的不一致，
    破坏 `04` §2.7.1「`X-Trace-Id` 出方向必填」的契约。
    """

    @app.exception_handler(AiEngineError)
    async def _handle_ai_engine_error(request: Request, exc: AiEngineError) -> JSONResponse:
        if (exc.http_status or 500) >= 500:
            logger.error("AI 引擎失败 code=%s status=%s", exc.error_code.value, exc.http_status, exc_info=exc)
        else:
            logger.warning("AI 引擎失败 code=%s status=%s", exc.error_code.value, exc.http_status)
        trace_id = _resolve_trace_id(request)
        return _error_response(
            trace_id, exc.http_status or 500, exc.as_response(trace_id=trace_id).model_dump()
        )

    @app.exception_handler(RequestValidationError)
    async def _handle_request_validation(request: Request, exc: RequestValidationError) -> JSONResponse:
        detail = _summarize_validation_errors(exc.errors())
        logger.warning("请求校验失败: %s", detail)
        trace_id = _resolve_trace_id(request)
        return _error_response(
            trace_id,
            400,
            InternalResponse.failure(
                AiErrorCode.VALIDATION_ERROR.value,
                f"{_validation_message()} {detail}",
                trace_id=trace_id,
            ).model_dump(),
        )

    @app.exception_handler(StarletteHTTPException)
    async def _handle_http_exception(request: Request, exc: StarletteHTTPException) -> JSONResponse:
        status = exc.status_code
        error_code = _STARLETTE_STATUS_TO_ERROR_CODE.get(
            status, AiErrorCode.VALIDATION_ERROR if 400 <= status < 500 else AiErrorCode.INTERNAL_ERROR
        )
        logger.warning("HTTP 异常 status=%s 映射为 code=%s", status, error_code.value)
        trace_id = _resolve_trace_id(request)
        return _error_response(
            trace_id,
            status,
            InternalResponse.failure(
                error_code.value, ERROR_CODE_MESSAGES[error_code], trace_id=trace_id
            ).model_dump(),
        )

    @app.exception_handler(Exception)
    async def _handle_unexpected(request: Request, exc: Exception) -> JSONResponse:
        logger.error("未捕获异常，已按 INTERNAL_ERROR 返回", exc_info=exc)
        trace_id = _resolve_trace_id(request)
        return _error_response(
            trace_id,
            500,
            InternalResponse.failure(
                AiErrorCode.INTERNAL_ERROR.value,
                ERROR_CODE_MESSAGES[AiErrorCode.INTERNAL_ERROR],
                trace_id=trace_id,
            ).model_dump(),
        )


def _resolve_trace_id(request: Request) -> str:
    """取本次请求的追踪号：优先 `scope["state"]`（中间件 unwind 后仍可读），再退回上下文变量。"""
    return tracing.trace_id_from_scope(request.scope) or tracing.current_or_generated_trace_id()


def _error_response(trace_id: str, status_code: int, payload: dict[str, Any]) -> JSONResponse:
    """构造错误响应，并显式回写 `X-Trace-Id`（见 `register_exception_handlers` 的说明）。"""
    return JSONResponse(
        status_code=status_code,
        content=payload,
        headers={tracing.TRACE_ID_HEADER: trace_id},
    )


ERROR_CODE_MESSAGES: dict[AiErrorCode, str] = {
    AiErrorCode.VALIDATION_ERROR: "请求参数校验失败。",
    AiErrorCode.AUTH_FORBIDDEN: "无权访问该资源。",
    AiErrorCode.RESOURCE_NOT_FOUND: "目标资源不存在或已被删除。",
    AiErrorCode.FEATURE_NOT_AVAILABLE: "该能力尚未开放，请等待后续版本。",
    AiErrorCode.E_SYS_02: "该自定义 Base URL 未通过安全校验，或上游返回了重定向。",
    AiErrorCode.E_RAG_01: "扫描件暂不支持，请上传文字版 PDF（OCR 将在 v1.1 开放）。",
    AiErrorCode.E_RAG_02: "课件解析多次重试后仍失败，请稍后手动重试或更换文件。",
    AiErrorCode.E_RAG_03: "知识库上下文读取暂时不可用，请稍后重试。",
    AiErrorCode.UPSTREAM_MODEL_AUTH_ERROR: "模型服务鉴权失败，请检查【BYOK 设置】中的 API Key 或模型名称是否正确。",
    AiErrorCode.UPSTREAM_MODEL_RATE_LIMITED: "上游模型触发速率或配额限制，请稍后重试。",
    AiErrorCode.UPSTREAM_MODEL_ERROR: "上游模型服务暂时异常，请稍后重试。",
    AiErrorCode.UPSTREAM_TIMEOUT: "响应超时，可使用原请求标识重试。",
    AiErrorCode.INTERNAL_ERROR: "系统繁忙，请稍后重试。",
}


def _validation_message() -> str:
    return ERROR_CODE_MESSAGES[AiErrorCode.VALIDATION_ERROR]


def _summarize_validation_errors(errors: list[dict[str, Any]]) -> str:
    """把 Pydantic 校验错误压缩成一行字段级提示（不含请求体原文，避免泄露用户内容）。"""
    parts: list[str] = []
    for error in errors[:10]:
        location = ".".join(str(item) for item in error.get("loc", ()))
        parts.append(f"{location}: {error.get('msg', 'invalid')}")
    return "; ".join(parts)
