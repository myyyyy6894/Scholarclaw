"""外部模型出站请求的传输基础（`NFR-REL-01`、`NFR-SEC-05`、`04` §6.2）。

职责边界：

* 构造**关闭重定向跟随**（`follow_redirects=False`）且**不读取代理环境变量**
  （`trust_env=False`）的异步客户端，避免 `HTTP_PROXY` 之类环境变量把出站流量带离受控路径；
* 按 `NFR-REL-01` 对 `429 / 502 / 503 / 504` 做最多 2 次指数退避重试（间隔 1s → 3s）；
* 把上游失败分类为 `app.core.exceptions` 中的确定性异常，**不把上游响应体原文当异常消息抛出**
  （响应体可能回显凭据或包含不可信内容）；
* 拒绝把 `3xx` 当作成功：重定向一律判为 `E-SYS-02`，避免绕过 `ssrf_guard` 的
  URL/IP 校验（TASK-203/204；网关的完整重试/分类策略属 TASK-301）。

本模块不含任何模型语义（探测/对话/向量化属 TASK-301/302），也不解析响应体。
"""

from __future__ import annotations

import asyncio
from collections.abc import Awaitable, Callable, Mapping
from typing import Any
from urllib.parse import urlsplit

import httpx

from app.core.exceptions import (
    AiErrorCode,
    AiEngineError,
    BaseUrlRejectedError,
    NonRetryableAiError,
    UpstreamModelAuthError,
    UpstreamModelError,
    UpstreamModelRateLimitedError,
    UpstreamTimeoutError,
)

#: 可安全重试的上游状态码（`NFR-REL-01` / `04` §6.2）。
RETRYABLE_UPSTREAM_STATUS = frozenset({429, 502, 503, 504})

#: 退避间隔（秒）。长度即最大重试次数：1s → 3s，共 2 次重试。
BACKOFF_SECONDS: tuple[float, ...] = (1.0, 3.0)

#: 上游返回非预期状态码时的可读提示（错误码仍取 `UPSTREAM_MODEL_ERROR`）。
UNEXPECTED_UPSTREAM_MESSAGE = "上游模型返回了未预期的状态，请检查模型名称与请求参数。"

SleepFunction = Callable[[float], Awaitable[None]]


def build_async_client(
    *,
    connect_timeout: float,
    read_timeout: float,
    write_timeout: float | None = None,
    pool_timeout: float | None = None,
    transport: httpx.AsyncBaseTransport | None = None,
) -> httpx.AsyncClient:
    """构造符合安全约束的出站客户端。

    :param transport: 仅用于测试注入（如 `httpx.MockTransport`）；生产环境保持 `None`。
    调用方负责在使用完毕后 `aclose()`。
    """
    timeout = httpx.Timeout(
        connect=connect_timeout,
        read=read_timeout,
        write=read_timeout if write_timeout is None else write_timeout,
        pool=read_timeout if pool_timeout is None else pool_timeout,
    )
    return httpx.AsyncClient(
        timeout=timeout,
        follow_redirects=False,
        trust_env=False,
        transport=transport,
    )


def classify_upstream_failure(status_code: int) -> AiEngineError:
    """把上游 HTTP 失败映射为确定性异常类型（`04` 第 7 章错误码）。"""
    if status_code in (401, 403, 404):
        return UpstreamModelAuthError()
    if status_code == 429:
        return UpstreamModelRateLimitedError()
    if 500 <= status_code <= 599:
        return UpstreamModelError()
    return NonRetryableAiError(AiErrorCode.UPSTREAM_MODEL_ERROR, UNEXPECTED_UPSTREAM_MESSAGE)


async def request_with_retry(
    client: httpx.AsyncClient,
    method: str,
    url: str,
    *,
    json_body: Mapping[str, Any] | None = None,
    headers: Mapping[str, str] | None = None,
    sleep: SleepFunction = asyncio.sleep,
) -> httpx.Response:
    """发送请求并按既定策略处理失败。

    :param sleep: 可注入的等待函数，测试中替换为记录型实现即可断言退避序列，无需真实等待。
    :raises UpstreamTimeoutError: 连接/读取超时（`NFR-REL-01` 不含超时重试）。
    :raises BaseUrlRejectedError: 上游返回 `3xx` 重定向。
    :raises UpstreamModelAuthError: 上游 401 / 403 / 404。
    :raises UpstreamModelRateLimitedError: 上游 429 且退避重试后仍受限。
    :raises UpstreamModelError: 上游 5xx 且退避重试后仍失败。
    """
    attempt = 0
    while True:
        try:
            response = await client.request(method, url, json=json_body, headers=headers)
        except httpx.TimeoutException as exc:
            raise UpstreamTimeoutError() from exc
        except httpx.HTTPError as exc:
            # 连接失败、DNS 失败等：按上游服务异常归类，且不重试（文档只对 429/5xx 规定重试）
            raise UpstreamModelError() from exc

        if 300 <= response.status_code < 400:
            raise BaseUrlRejectedError()

        if response.status_code < 400:
            return response

        failure = classify_upstream_failure(response.status_code)
        if failure.retryable and attempt < len(BACKOFF_SECONDS):
            await sleep(BACKOFF_SECONDS[attempt])
            attempt += 1
            continue
        raise failure


def safe_url_for_log(url: str) -> str:
    """日志用 URL：**丢弃查询串与片段**。

    部分模型供应商把密钥放在查询参数里（如 `?key=...`），模式匹配式脱敏无法覆盖任意参数名，
    因此这里直接不记录查询串，从结构上杜绝泄露。
    """
    parts = urlsplit(url)
    if not parts.scheme and not parts.netloc:
        return parts.path
    port = f":{parts.port}" if parts.port else ""
    return f"{parts.scheme}://{parts.hostname or ''}{port}{parts.path}"
