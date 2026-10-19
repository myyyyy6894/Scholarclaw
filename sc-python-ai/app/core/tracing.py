"""全链路追踪上下文（`NFR-REL-02`、`02` §11、`04` §2.7.1）。

* `trace_id` 由 Java 在入口生成后经 `X-Trace-Id` 透传；Python 接受合法入站值，否则自行生成，
  并在响应头原样返回，保证 Java ↔ Python 调用链可串联。
* **身份信任边界**：`X-User-Id` 只有在 `X-Internal-Token` 校验通过后才可信任（`04` §6.1.2）。
  因此本模块只提供绑定入口，绑定动作由 :mod:`app.core.security` 在鉴权成功后执行——
  中间件本身**不**从请求头读取 `X-User-Id`，避免出现"未鉴权却已信任身份"的窗口；
  请求结束时中间件清空身份（:func:`clear_user_id`），避免 worker 复用导致跨请求串用户。
* 非 HTTP 起点（MQ 消费者、定时任务）按 `02` §11 使用 `tr_cron_*` 前缀生成追踪号。
"""

from __future__ import annotations

import re
import secrets
from contextvars import ContextVar, Token

from starlette.datastructures import Headers, MutableHeaders

TRACE_ID_HEADER = "X-Trace-Id"
USER_ID_HEADER = "X-User-Id"

#: `scope["state"]` 中保存追踪号的键。
#: 未捕获异常由 Starlette 的 `ServerErrorMiddleware` 兜底，它在用户中间件**之外**执行，
#: 此时上下文变量已被中间件在 `finally` 中重置；把追踪号同时写进 scope state，
#: 异常处理器才能拿到与本次请求一致的追踪号（否则响应体与响应头会各生成一个）。
TRACE_ID_STATE_KEY = "sc_trace_id"

HTTP_TRACE_PREFIX = "tr_"
CRON_TRACE_PREFIX = "tr_cron_"

_ACCEPTABLE_INBOUND_TRACE_ID = re.compile(r"^tr_[0-9a-f]{12}$")

_trace_id_var: ContextVar[str | None] = ContextVar("sc_trace_id", default=None)
_user_id_var: ContextVar[str | None] = ContextVar("sc_user_id", default=None)


def generate_trace_id() -> str:
    """生成 HTTP 入口追踪号：`tr_` + 12 位十六进制。"""
    return HTTP_TRACE_PREFIX + secrets.token_hex(6)


def generate_cron_trace_id() -> str:
    """生成非 HTTP 起点追踪号（MQ 消费者 / 定时任务）。"""
    return CRON_TRACE_PREFIX + secrets.token_hex(6)


def is_acceptable_inbound_trace_id(value: str | None) -> bool:
    """只接受 `04` §2.7.1 规定的格式，避免脏值污染日志与调用链。"""
    return bool(value) and bool(_ACCEPTABLE_INBOUND_TRACE_ID.match(value))


def current_trace_id() -> str | None:
    return _trace_id_var.get()


def current_user_id() -> str | None:
    return _user_id_var.get()


def current_or_generated_trace_id() -> str:
    """构造对外响应时使用：优先当前追踪号，无则按 `02` §11 生成 `tr_cron_*`。"""
    return _trace_id_var.get() or generate_cron_trace_id()


def bind_trace_id_to_scope(scope: dict, trace_id: str) -> None:
    """把追踪号写入 `scope["state"]`，供异常处理器在中间件 unwind 后仍能读取。"""
    scope.setdefault("state", {})[TRACE_ID_STATE_KEY] = trace_id


def trace_id_from_scope(scope: dict) -> str | None:
    return (scope.get("state") or {}).get(TRACE_ID_STATE_KEY)


def bind_trace_id(trace_id: str) -> Token[str | None]:
    return _trace_id_var.set(trace_id)


def bind_user_id(user_id: str | None) -> Token[str | None]:
    """仅在内部令牌校验通过后调用（`04` §6.1.2）。"""
    return _user_id_var.set(user_id)


def reset(token: Token[str | None]) -> None:
    _trace_id_var.reset(token)


def reset_user_id(token: Token[str | None]) -> None:
    _user_id_var.reset(token)


def clear_user_id() -> None:
    """清空用户身份上下文（请求结束后由中间件调用）。

    为什么用 `set(None)` 而不是 `reset(token)`：绑定发生在依赖（`app.core.security`）里，
    其 `Token` 与中间件不在同一调用栈上；跨上下文 `reset` 会抛 `ValueError`，
    而这里只需要"请求结束时身份一定为空"这一确定性结果。
    """
    _user_id_var.set(None)


def clear() -> None:
    _trace_id_var.set(None)
    _user_id_var.set(None)


class TraceIdMiddleware:
    """纯 ASGI 中间件：确定 `trace_id`、写入上下文、在响应头返回。

    刻意不使用 `BaseHTTPMiddleware`：后者对 SSE 等流式响应存在缓冲/中断风险，
    而本服务后续要提供 SSE 问答接口（TASK-604）。
    """

    def __init__(self, app) -> None:  # noqa: ANN001 - ASGI 应用协议
        self.app = app

    async def __call__(self, scope, receive, send) -> None:  # noqa: ANN001 - ASGI 协议
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        inbound = Headers(scope=scope).get(TRACE_ID_HEADER)
        trace_id = inbound if is_acceptable_inbound_trace_id(inbound) else generate_trace_id()
        token = bind_trace_id(trace_id)
        bind_trace_id_to_scope(scope, trace_id)

        async def send_with_trace_header(message):  # noqa: ANN001 - ASGI 消息
            if message["type"] == "http.response.start":
                MutableHeaders(scope=message).append(TRACE_ID_HEADER, trace_id)
            await send(message)

        try:
            await self.app(scope, receive, send_with_trace_header)
        finally:
            reset(token)
            # 用户身份同样必须在请求结束时清空：ASGI worker 复用任务/线程，
            # 残留的 user_id 会让下一条无身份的请求带着上一个用户的 ID 打日志。
            clear_user_id()
