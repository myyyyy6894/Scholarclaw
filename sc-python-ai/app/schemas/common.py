"""统一响应信封 `InternalResponse[T]`（`04` §2.3.1）。

与 Java 侧 `com.scholarclaw.common.response.Result<T>` 字段完全一致：
`code` / `message` / `data` / `trace_id` / `timestamp`。禁止新增第二套包装。

* `code` 成功固定为 `"OK"`，失败取 `04` 第 7 章的稳定错误码；
* `timestamp` 为带偏移量的 ISO-8601 字符串，基准时区取应用时区（`app.core.config`），
  不随容器/宿主机默认时区漂移；
* `data` 为 `None` 时仍保留键名（`04` §2.5.5）。
"""

from __future__ import annotations

from typing import Generic, TypeVar

from pydantic import BaseModel

from app.core import tracing
from app.core.config import format_timestamp, now_in_active_timezone

SUCCESS_CODE = "OK"
SUCCESS_MESSAGE = "操作成功"

T = TypeVar("T")


class InternalResponse(BaseModel, Generic[T]):
    code: str
    message: str
    data: T | None = None
    trace_id: str
    timestamp: str

    @classmethod
    def ok(
        cls, data: T | None = None, *, message: str = SUCCESS_MESSAGE, trace_id: str | None = None
    ) -> InternalResponse[T]:
        return cls(
            code=SUCCESS_CODE,
            message=message,
            data=data,
            trace_id=trace_id or tracing.current_or_generated_trace_id(),
            timestamp=format_timestamp(now_in_active_timezone()),
        )

    @classmethod
    def failure(
        cls, code: str, message: str, data: T | None = None, *, trace_id: str | None = None
    ) -> InternalResponse[T]:
        """失败信封。

        :param message: 必须已是脱敏后的可读文案，不得包含密钥、连接串、内部地址或堆栈。
        :param trace_id: 显式指定追踪号。异常处理器运行在用户中间件之外，此时上下文变量
            可能已被重置，必须由调用方传入与响应头一致的追踪号。
        """
        return cls(
            code=code,
            message=message,
            data=data,
            trace_id=trace_id or tracing.current_or_generated_trace_id(),
            timestamp=format_timestamp(now_in_active_timezone()),
        )
