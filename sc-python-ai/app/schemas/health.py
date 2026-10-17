"""健康检查响应契约（`04` §4.9.8）。

`data` 内只允许出现服务标识、存活状态与依赖存活状态；不得包含连接串、凭据、
内部地址或其他敏感信息（`04` `API-CONF-03`）。
"""

from __future__ import annotations

from pydantic import BaseModel, Field

SERVICE_NAME = "sc-python-ai"


class HealthData(BaseModel):
    service: str = Field(default=SERVICE_NAME, description="服务标识")
    status: str = Field(description="聚合存活状态：UP / DOWN")
    dependencies: dict[str, str] = Field(
        default_factory=dict,
        description="已注册探针的依赖存活状态，例如 shared_storage_ro = UP",
    )
