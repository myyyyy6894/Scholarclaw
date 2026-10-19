"""健康检查响应契约（`04` §4.9.8）。

`data` 内只允许出现服务标识、存活状态、可公开的依赖存活状态与跨服务观测；
不得包含连接串、凭据、内部地址或其他敏感信息（`04` `API-CONF-03`）。
"""

from __future__ import annotations

from pydantic import BaseModel, Field

SERVICE_NAME = "sc-python-ai"


class HealthData(BaseModel):
    service: str = Field(default=SERVICE_NAME, description="服务标识")
    status: str = Field(description="聚合存活状态：UP / DOWN，只由 dependencies 决定")
    dependencies: dict[str, str] = Field(
        default_factory=dict,
        description="自身依赖的存活状态（参与 status 判定），例如 shared_storage_ro = UP、chromadb = UP",
    )
    peers: dict[str, str] = Field(
        default_factory=dict,
        description=(
            "跨服务连通性观测，**不参与 status 判定**。"
            "本服务当前恒为空：Python → Java 方向的连通性由容器 healthcheck 持续验证"
            "（sc-python-ai/healthcheck.py），不放进响应体——"
            "若两侧健康接口互相调用对方，会形成无限递归（Java 健康 → Python 健康 → Java 健康…）。"
            "字段保留是为了与 sc-java-backend 的响应保持同构，便于统一解析。"
        ),
    )
