"""chat_response 公共解析辅助单元测试。"""

from __future__ import annotations

import pytest

from app.services.gateway.chat_response import (
    ChatResponseError,
    extract_chat_content,
    strip_code_fence,
)


def test_extract_chat_content_returns_valid_text() -> None:
    content = extract_chat_content({"choices": [{"message": {"content": '{"ok": true}'}}]})
    assert content == '{"ok": true}'


@pytest.mark.parametrize(
    ("payload", "expected_code"),
    [
        ("not-a-dict", "missing_choices"),
        ({}, "missing_choices"),
        ({"choices": []}, "missing_choices"),
        ({"choices": ["bad"]}, "invalid_choice"),
        ({"choices": [{}]}, "missing_message"),
        ({"choices": [{"message": None}]}, "missing_message"),
        ({"choices": [{"message": {}}]}, "empty_content"),
        ({"choices": [{"message": {"content": None}}]}, "empty_content"),
        ({"choices": [{"message": {"content": "   \n\t"}}]}, "empty_content"),
    ],
)
def test_extract_chat_content_rejects_malformed_shapes(
    payload: object, expected_code: str
) -> None:
    with pytest.raises(ChatResponseError) as excinfo:
        extract_chat_content(payload)
    assert excinfo.value.code == expected_code


def test_strip_code_fence_removes_markdown_json_fence() -> None:
    raw = '  ```json\n{"a": 1,\n"b": 2}\n```  '
    assert strip_code_fence(raw) == '{"a": 1,\n"b": 2}'


def test_strip_code_fence_preserves_plain_text() -> None:
    raw = '  {"a": 1}  '
    assert strip_code_fence(raw) == '{"a": 1}'
