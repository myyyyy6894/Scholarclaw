"""一次模型调用所需的最小配置（`04` §4.9.1 的 `chat_config` / `embedding_config`）。

`api_key` 是**明文**，只在当次内存请求体内使用（`03` §7.3：明文只在受
`X-Internal-Token` 保护的内部请求中按任务透传），不得写日志、不得缓存、
不得出现在异常消息里。
"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class ModelEndpoint:
    provider: str
    base_url: str
    api_key: str | None
    model_name: str
