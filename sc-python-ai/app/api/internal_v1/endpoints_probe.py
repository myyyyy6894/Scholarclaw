"""内部模型连通性探测 `POST /internal/ai/v1/model/probe`（`04` §4.9.1 `API-INT-AI-001`）。

* 鉴权：`X-Internal-Token`（跨容器调用必须携带；健康检查的环回例外不适用于本接口）。
* 语义：**不重试**（配置错误要立刻反馈）；上游失败按 `04` 第 7 章映射为确定性错误码，
  不把上游错误正文或地址透传给调用方。
* 数据最小化：请求体里的 `api_key` 只用于构造出站请求头，不写日志、不回显、不缓存。
"""

from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends

from app.core.config import get_settings
from app.core.exceptions import AiErrorCode, NonRetryableAiError
from app.core.logging import get_logger
from app.core.security import require_internal_identity
from app.schemas.common import InternalResponse
from app.schemas.probe import ModelEndpointPayload, ModelProbeData, ModelProbeRequest
from app.services.gateway.model_probe import ModelEndpointConfig, probe_models

logger = get_logger(__name__)

router = APIRouter(tags=["internal-probe"])


def _to_config(payload: ModelEndpointPayload | None) -> ModelEndpointConfig | None:
    if payload is None:
        return None
    return ModelEndpointConfig(
        provider=payload.provider,
        base_url=payload.base_url,
        api_key=payload.api_key,
        model_name=payload.model_name,
    )


@router.post(
    "/model/probe",
    response_model=InternalResponse[ModelProbeData],
    summary="模型连通性与真实向量维度探测",
    description="调用候选模型地址做最小探测；失败按 04 第 7 章返回确定性错误码。",
)
async def probe(
    body: ModelProbeRequest,
    header_user_id: Annotated[str, Depends(require_internal_identity)],
) -> InternalResponse[ModelProbeData]:
    if body.user_id != header_user_id:
        raise NonRetryableAiError(
            AiErrorCode.VALIDATION_ERROR,
            "请求体 user_id 与 X-User-Id 不一致，已拒绝探测。",
        )
    outcome = await probe_models(
        get_settings(),
        body.probe_type,
        _to_config(body.chat_config),
        _to_config(body.embedding_config),
    )
    logger.info("模型探测完成 user_id=%s probe_type=%s chat=%s embedding=%s dim=%s latency_ms=%s",
                body.user_id, body.probe_type, outcome.chat_reachable, outcome.embedding_reachable,
                outcome.actual_dim, outcome.latency_ms)
    return InternalResponse.ok(
        ModelProbeData(
            chat_reachable=outcome.chat_reachable,
            embedding_reachable=outcome.embedding_reachable,
            actual_dim=outcome.actual_dim,
            latency_ms=outcome.latency_ms,
        ),
        message="Probe succeeded",
    )
