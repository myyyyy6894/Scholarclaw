"""`API-INT-AI-004` 向量清理请求/响应（`04` §4.9.4）。"""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, model_validator


class VectorCleanupRequest(BaseModel):
    scope: Literal["DOCUMENT", "COURSE_COLLECTION", "USER_ALL"]
    user_id: str
    course_id: str | None = None
    course_index_version: int | None = None
    doc_id: str | None = None
    stale_parse_version: int | None = None

    @model_validator(mode="after")
    def validate_scope(self) -> "VectorCleanupRequest":
        if not self.user_id.isdigit():
            raise ValueError("user_id 必须为十进制字符串")
        if self.scope in {"DOCUMENT", "COURSE_COLLECTION"} and not (
                self.course_id and self.course_id.isdigit()):
            raise ValueError("该 scope 必须提供十进制 course_id")
        if self.scope == "DOCUMENT" and not (self.doc_id and self.doc_id.isdigit()):
            raise ValueError("DOCUMENT scope 必须提供十进制 doc_id")
        if self.course_index_version is not None and self.course_index_version < 1:
            raise ValueError("course_index_version 必须 >= 1")
        if self.stale_parse_version is not None and self.stale_parse_version < 1:
            raise ValueError("stale_parse_version 必须 >= 1")
        return self


class VectorCleanupData(BaseModel):
    scope: str
    cleaned: bool


class VectorCollectionData(BaseModel):
    """`GET /rag/collections` 的只读对账结果（不含向量内容）。"""

    names: list[str]
    count: int
