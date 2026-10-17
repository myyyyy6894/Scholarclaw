"""容器内部健康检查 `GET /internal/ai/v1/health`（`04` §4.9.8）。

* 鉴权：按 `04` `API-CONF-03` 默认方案，仅本接口允许容器本机环回地址免 `X-Internal-Token`
  访问（便于 Docker Compose `healthcheck` 无需转义令牌）；跨容器调用仍需携带令牌。
* 响应：始终返回 HTTP 200 且 `code = "OK"`（信封契约由 `04` §2.3 固定，第 7 章未定义
  "依赖不健康"这类 HTTP 语义），依赖状态体现在 `data.status` 与 `data.dependencies`。
  TASK-104 编写 Compose 健康检查时需据此判断：`curl -fsS` 只能证明进程存活，
  若要按依赖状态判定，需进一步检查响应体中的 `status` 字段。
* 数据最小化：只返回服务标识、状态与依赖存活状态，绝不返回路径、地址或凭据。
"""

from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends

from app.core.config import get_settings
from app.core.health import STATUS_DOWN, build_dependency_probes, overall_status, run_dependency_probes
from app.core.logging import get_logger
from app.core.security import require_internal_token_or_loopback
from app.schemas.common import InternalResponse
from app.schemas.health import HealthData

logger = get_logger(__name__)

router = APIRouter(tags=["internal-health"])


@router.get(
    "/health",
    response_model=InternalResponse[HealthData],
    summary="容器内部健康检查",
    description="返回本服务存活状态与已注册依赖探针的存活状态；不返回任何敏感信息。",
)
async def health(
    _: Annotated[None, Depends(require_internal_token_or_loopback)],
) -> InternalResponse[HealthData]:
    settings = get_settings()
    dependencies = run_dependency_probes(build_dependency_probes(settings))
    status = overall_status(dependencies)
    if status == STATUS_DOWN:
        logger.warning("健康检查依赖不可用 status=%s dependencies=%s", status, dependencies)
    return InternalResponse.ok(HealthData(status=status, dependencies=dependencies), message="Healthy")
