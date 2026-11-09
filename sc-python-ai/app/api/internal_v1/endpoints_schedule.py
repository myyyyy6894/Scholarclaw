"""Internal stateless schedule suggestions, isolated by caller identity."""

from typing import Annotated

from fastapi import APIRouter, Depends

from app.core.config import get_settings
from app.core.exceptions import AiErrorCode, NonRetryableAiError
from app.core.security import require_internal_identity
from app.schemas.common import InternalResponse
from app.schemas.schedule_extract import ScheduleImageExtractRequest, ScheduleTextExtractData, ScheduleTextExtractRequest
from app.services.schedule.image_extractor import extract_image
from app.services.schedule.text_extractor import extract_text

router = APIRouter(tags=["internal-schedule"])


@router.post("/schedule/extract-text", response_model=InternalResponse[ScheduleTextExtractData])
def extract_schedule_text(
    body: ScheduleTextExtractRequest,
    header_user_id: Annotated[str, Depends(require_internal_identity)],
) -> InternalResponse[ScheduleTextExtractData]:
    if body.user_id != header_user_id:
        raise NonRetryableAiError(AiErrorCode.VALIDATION_ERROR, "请求体用户与调用身份不一致。")
    return InternalResponse.ok(ScheduleTextExtractData(rows=extract_text(body.raw_text, body.week_count)))


@router.post("/schedule/extract-image", response_model=InternalResponse[ScheduleTextExtractData])
async def extract_schedule_image(
    body: ScheduleImageExtractRequest,
    header_user_id: Annotated[str, Depends(require_internal_identity)],
) -> InternalResponse[ScheduleTextExtractData]:
    if body.user_id != header_user_id:
        raise NonRetryableAiError(AiErrorCode.VALIDATION_ERROR, "请求体用户与调用身份不一致。")
    return InternalResponse.ok(await extract_image(get_settings(), body))
