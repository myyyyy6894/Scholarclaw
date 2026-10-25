"""内部意图路由与 DDL 结构化提取 `POST /internal/ai/v1/agent/route-and-extract`。

对应 `04` §4.9.2 `API-INT-AI-002`：

* 当前阶段（TASK-701）只实现 `FORCE_DDL_EXTRACT`；
* `AUTO_ROUTE` 的完整意图路由属 TASK-706，未实现前返回稳定的
  `501 FEATURE_NOT_AVAILABLE`，**不返回猜测意图，也不伪造路由成功**；
* 本接口不写 MySQL、不访问 Redis/ChromaDB、不发布 RabbitMQ 消息；
* 模型配置按当次请求的 `chat_config` 透传，不读取任何全局默认 Key。
"""

from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends

from app.core.config import get_settings
from app.core.exceptions import AiErrorCode, NonRetryableAiError
from app.core.security import require_internal_identity
from app.schemas.common import InternalResponse
from app.schemas.ddl_extract import (
    DdlExtractRequest,
    ExtractionMode,
    RouteAndExtractData,
)
from app.services.ddl.extractor import (
    DdlExtractionRequest,
    DdlExtractor,
    DdlExtractionResult,
)
from app.services.gateway.model_endpoint import ModelEndpoint

router = APIRouter(tags=["internal-agent"])

AUTO_ROUTE_UNAVAILABLE_MESSAGE = (
    "AUTO_ROUTE 意图路由尚未实现（TASK-706），当前仅支持 FORCE_DDL_EXTRACT。"
)


def get_ddl_extractor() -> DdlExtractor:
    """FastAPI 依赖：测试可覆盖此函数注入 FakeGateway。"""
    return DdlExtractor(get_settings())


@router.post(
    "/agent/route-and-extract",
    response_model=InternalResponse[RouteAndExtractData],
    summary="内部单一意图路由与 DDL 结构化提取",
    description=(
        "TASK-701 仅支持 FORCE_DDL_EXTRACT；AUTO_ROUTE 返回 501 FEATURE_NOT_AVAILABLE，"
        "不返回猜测意图。"
    ),
)
async def route_and_extract(
    body: DdlExtractRequest,
    header_user_id: Annotated[str, Depends(require_internal_identity)],
    extractor: Annotated[DdlExtractor, Depends(get_ddl_extractor)],
) -> InternalResponse[RouteAndExtractData]:
    if body.user_id != header_user_id:
        raise NonRetryableAiError(
            AiErrorCode.VALIDATION_ERROR,
            "请求体 user_id 与 X-User-Id 不一致，已拒绝 DDL 提取。",
        )

    if body.mode is not ExtractionMode.FORCE_DDL_EXTRACT:
        raise NonRetryableAiError(
            AiErrorCode.FEATURE_NOT_AVAILABLE,
            AUTO_ROUTE_UNAVAILABLE_MESSAGE,
            http_status=501,
        )

    result: DdlExtractionResult = await extractor.extract(
        DdlExtractionRequest(
            user_id=body.user_id,
            raw_text=body.raw_text,
            reference_time=body.reference_time,
            user_timezone=body.user_timezone,
            candidate_courses=tuple(body.candidate_courses),
            chat_endpoint=ModelEndpoint(
                provider=body.chat_config.provider,
                base_url=body.chat_config.base_url,
                api_key=body.chat_config.api_key,
                model_name=body.chat_config.model_name,
            ),
        )
    )
    return InternalResponse.ok(
        RouteAndExtractData(
            intent="DDL_EXTRACT",
            intent_confidence=result.intent_confidence,
            ddl_payload=result.payload,
        ),
        message="Extraction completed",
    )
