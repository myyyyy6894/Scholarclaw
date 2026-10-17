"""凭据与数据库隔离红线的机械验证（TASK-102 验收条件）。

TASK-102 的三条验收条件是"不连接 MySQL/Redis""不读取 `SC_MASTER_KEY` 或数据库凭据"。
口头承诺没有意义，因此这里把它们变成源码级断言：只要有人在 `app/` 里写了
`pymysql`/`redis` 依赖或直读敏感环境变量，测试立刻失败。
"""

from __future__ import annotations

import ast
import re
from pathlib import Path

import pytest

import app
from app.core import config

APP_ROOT = Path(app.__file__).parent

#: 永久禁止引入的客户端库（Python 侧不得直连 MySQL / Redis）。
FORBIDDEN_DEPENDENCY_MODULES = (
    "pymysql",
    "MySQLdb",
    "mysql",
    "mysql.connector",
    "aiomysql",
    "asyncmy",
    "sqlalchemy",
    "redis",
    "aioredis",
)

_ENV_ACCESS_PATTERNS = (
    re.compile(r"""os\.environ\s*\[\s*["']([A-Z0-9_]+)["']"""),
    re.compile(r"""os\.environ\.get\(\s*["']([A-Z0-9_]+)["']"""),
    re.compile(r"""os\.getenv\(\s*["']([A-Z0-9_]+)["']"""),
)


def _python_sources() -> list[Path]:
    return sorted(APP_ROOT.rglob("*.py"))


def test_source_tree_is_not_empty() -> None:
    assert _python_sources(), "源码扫描若匹配不到文件，下面的断言会静默通过"


@pytest.mark.parametrize("module_name", FORBIDDEN_DEPENDENCY_MODULES)
def test_no_forbidden_database_or_cache_client_is_imported(module_name: str) -> None:
    for path in _python_sources():
        tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                imported = [alias.name for alias in node.names]
            elif isinstance(node, ast.ImportFrom):
                imported = [node.module or ""]
            else:
                continue
            for name in imported:
                root = name.split(".")[0]
                assert root != module_name.split(".")[0], (
                    f"{path.relative_to(APP_ROOT)} 引入了被禁止的依赖 {name}；"
                    "Python 侧不得直连 MySQL / Redis（02 §5.1、05 §3.1）"
                )


def test_no_direct_environment_access_outside_whitelist() -> None:
    """任何直接读取环境变量的字面量都必须在白名单内。"""
    for path in _python_sources():
        source = path.read_text(encoding="utf-8")
        for pattern in _ENV_ACCESS_PATTERNS:
            for name in pattern.findall(source):
                assert name in config.ALLOWED_ENV_VARS, (
                    f"{path.relative_to(APP_ROOT)} 直接读取了未登记的环境变量 {name}；"
                    "新增配置必须先登记到 app/core/config.py 的白名单并说明用途"
                )


def _string_constants_excluding_docstrings(path: Path) -> list[str]:
    """提取源码中的字符串字面量（排除 docstring）。

    排除 docstring 是必要的：允许在文档里说明"本服务不读取 `SC_MASTER_KEY`"，
    但**代码路径**中不允许出现这些名字。
    """
    tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    docstring_nodes: set[int] = set()
    for node in ast.walk(tree):
        if isinstance(node, (ast.Module, ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef)):
            body = getattr(node, "body", [])
            if body and isinstance(body[0], ast.Expr) and isinstance(body[0].value, ast.Constant) and isinstance(
                body[0].value.value, str
            ):
                docstring_nodes.add(id(body[0].value))

    return [
        node.value
        for node in ast.walk(tree)
        if isinstance(node, ast.Constant)
        and isinstance(node.value, str)
        and id(node) not in docstring_nodes
    ]


def test_forbidden_credentials_are_only_declared_not_used() -> None:
    """敏感变量名只允许作为"禁止清单"的字面量出现在 config.py，不得进入任何代码路径。"""
    for path in _python_sources():
        constants = _string_constants_excluding_docstrings(path)
        for name in config.FORBIDDEN_ENV_VARS:
            occurrences = sum(1 for value in constants if value == name)
            if path.name == "config.py":
                assert occurrences == 1, f"{path.name} 对 {name} 的引用方式超出预期，请人工复核"
            else:
                assert occurrences == 0, (
                    f"{path.relative_to(APP_ROOT)} 的代码中出现了敏感变量名 {name}；"
                    "本服务不得读取主密钥或数据库凭据"
                )


def test_settings_dataclass_has_no_credential_fields() -> None:
    forbidden_tokens = ("master_key", "db_password", "redis_password", "jwt_secret")

    for name in config.Settings.__dataclass_fields__:
        lowered = name.lower()
        assert all(token not in lowered for token in forbidden_tokens), (
            f"配置字段 {name} 疑似承载凭据；本服务不得持有主密钥或数据库凭据（02 §13.2）"
        )
