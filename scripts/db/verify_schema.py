#!/usr/bin/env python3
"""只读核对 ScholarClaw MySQL 表结构，不依赖被 Git 忽略的本地文档。

数据字典由随仓库交付的 ``scripts/db/schema_spec.json`` 提供，统一覆盖：
- ``mvp``：V1.0.0–V1.0.8 业务核心表与辅助表（含 QQ 官方机器人字段及决策回执表）；
- ``schedule``：V1.0.9–V1.0.11 课表基础与导入表；
- ``planning``：V1.0.12–V1.0.13 时间规划输入与学习计划表。

用法示例（在仓库根目录或任意目录）：
    python3 scripts/db/verify_schema.py --scope all
    python3 scripts/db/verify_schema.py --scope mvp --quiet
    MYSQL_PWD=... python3 scripts/db/verify_schema.py --host 127.0.0.1 --port 23306 --database scholarclaw --user root

退出码：0 = 全部一致；1 = 结构存在不一致；2 = 参数、规范文件或数据库连接/查询失败。
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from collections.abc import Callable
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_SPEC_PATH = Path(__file__).resolve().with_name("schema_spec.json")
VALID_SCOPES = ("mvp", "schedule", "planning")
TABLE_NAME_RE = re.compile(r"^[a-z0-9_]+$")

QueryFn = Callable[[str], list[list[str]]]


class SchemaQueryError(RuntimeError):
    """数据库连接或只读结构查询失败。"""


def load_spec(spec_path: Path = DEFAULT_SPEC_PATH) -> dict[str, Any]:
    """加载随仓库交付的机器可读数据库结构规范。"""
    try:
        data = json.loads(spec_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise SchemaQueryError(f"无法读取结构规范文件 {spec_path}：{exc}") from exc
    if not isinstance(data, dict) or not isinstance(data.get("scopes"), dict):
        raise SchemaQueryError(f"结构规范文件格式无效：{spec_path}")
    return data


def resolve_scopes(raw_scopes: list[str] | None, spec: dict[str, Any]) -> list[str]:
    """解析并去重 scope 列表，支持 ``all`` 与逗号分隔写法。"""
    available = list(spec["scopes"].keys())
    if not raw_scopes:
        return available
    resolved: list[str] = []
    for item in raw_scopes:
        for token in item.split(","):
            name = token.strip()
            if not name:
                continue
            if name == "all":
                for scope in available:
                    if scope not in resolved:
                        resolved.append(scope)
            elif name in spec["scopes"]:
                if name not in resolved:
                    resolved.append(name)
            else:
                raise SchemaQueryError(
                    f"未知校验范围 {name!r}，可选：all, {', '.join(available)}"
                )
    if not resolved:
        raise SchemaQueryError("未指定有效的校验范围。")
    return resolved


def make_tcp_query_fn(host: str, port: int, database: str, user: str) -> QueryFn:
    """构造基于 TCP 直连 mysql CLI 的只读查询函数（密码走 MYSQL_PWD）。"""
    base_cmd = [
        "mysql",
        "--no-defaults",
        "--protocol=TCP",
        "--connect-timeout=3",
        "-N",
        "-B",
        "-h",
        host,
        "-P",
        str(port),
        "-u",
        user,
        "-D",
        database,
    ]

    def query(sql: str) -> list[list[str]]:
        try:
            out = subprocess.run(
                [*base_cmd, "-e", sql],
                capture_output=True,
                text=True,
                check=False,
            )
        except OSError as exc:
            raise SchemaQueryError(f"执行 mysql 客户端失败：{exc}") from exc
        if out.returncode != 0:
            detail = (out.stderr or out.stdout).strip()
            msg = "MySQL 结构查询失败，请检查目标和连接权限。"
            if detail:
                msg = f"{msg} ({detail.splitlines()[-1]})"
            raise SchemaQueryError(msg)
        return [line.rstrip("\r\n").split("\t") for line in out.stdout.splitlines() if line != ""]

    return query


def make_compose_query_fn(service: str, database: str) -> QueryFn:
    """构造基于 docker compose exec 的只读查询函数。"""
    if not TABLE_NAME_RE.fullmatch(database):
        raise SchemaQueryError(f"非法数据库名称：{database!r}")

    def query(sql: str) -> list[list[str]]:
        cmd = [
            "docker",
            "compose",
            "exec",
            "-T",
            service,
            "sh",
            "-c",
            'mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" -N -B -D "$1"',
            "_",
            database,
        ]
        try:
            out = subprocess.run(
                cmd,
                cwd=REPO_ROOT,
                input=sql + "\n",
                capture_output=True,
                text=True,
                check=False,
            )
        except OSError as exc:
            raise SchemaQueryError(f"执行 docker compose 失败：{exc}") from exc
        if out.returncode != 0:
            detail = (out.stderr or out.stdout).strip()
            raise SchemaQueryError(f"容器 MySQL 查询失败：{detail}")
        return [line.rstrip("\r\n").split("\t") for line in out.stdout.splitlines() if line != ""]

    return query


def verify_database(
    query_fn: QueryFn,
    spec: dict[str, Any],
    scopes: list[str],
) -> tuple[list[str], dict[str, int]]:
    """按给定范围执行只读 schema 核对，返回 (问题列表, 统计计数)。"""
    expected_tables: dict[str, dict[str, Any]] = {}
    for scope in scopes:
        for table_name, table_spec in spec["scopes"][scope].items():
            if not TABLE_NAME_RE.fullmatch(table_name):
                raise SchemaQueryError(f"规范包含非法表名：{table_name!r}")
            expected_tables[table_name] = table_spec

    problems: list[str] = []
    checks = 0
    check_mvp_meta = "mvp" in scopes

    # 1. 表集合存在性与禁用/迁移表校验
    live_tables = {
        row[0]
        for row in query_fn(
            "SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE()"
        )
    }
    for table_name in expected_tables:
        checks += 1
        if table_name not in live_tables:
            problems.append(f"缺表：{table_name}")
    if check_mvp_meta:
        migration_table = spec.get("migration_table")
        if migration_table:
            checks += 1
            if migration_table not in live_tables:
                problems.append(f"缺表：{migration_table}（迁移版本记录表）")
        for forbidden in spec.get("forbidden_tables", []):
            checks += 1
            if forbidden in live_tables:
                problems.append(f"多表：{forbidden} 属后续扩展表，不得提前创建")

    table_list_sql = ", ".join(f"'{name}'" for name in sorted(expected_tables))

    # 2. 字段：名称 / 类型 / 可空 / 默认值 / 额外标记
    live_columns: dict[str, dict[str, dict[str, str]]] = {}
    for row in query_fn(
        "SELECT table_name, column_name, column_type, is_nullable, extra, "
        "IFNULL(column_default, '<NULL>') "
        "FROM information_schema.columns "
        f"WHERE table_schema=DATABASE() AND table_name IN ({table_list_sql}) "
        "ORDER BY table_name, ordinal_position"
    ):
        table_name, col_name, col_type, nullable, extra, default = row
        live_columns.setdefault(table_name, {})[col_name] = {
            "type": col_type,
            "nullable": nullable,
            "extra": extra,
            "default": default,
        }

    total_columns = 0
    for table_name, table_spec in expected_tables.items():
        fields = table_spec["columns"]
        total_columns += len(fields)
        if table_name not in live_tables:
            continue
        live = live_columns.get(table_name, {})
        expected_names = [field["name"] for field in fields]
        checks += 1
        missing_cols = [name for name in expected_names if name not in live]
        if missing_cols:
            problems.append(f"{table_name} 缺字段：{missing_cols}")
        extra_cols = sorted(set(live) - set(expected_names))
        checks += 1
        if extra_cols:
            problems.append(f"{table_name} 多出未定义字段：{extra_cols}")

        for field in fields:
            col_name = field["name"]
            if col_name not in live:
                continue
            actual = live[col_name]

            checks += 1
            if field["type"].lower() != actual["type"].lower():
                problems.append(
                    f"{table_name}.{col_name} 类型不符：期望 {field['type']} / 实际 {actual['type']}"
                )

            checks += 1
            expected_nullable = "YES" if field["nullable"] else "NO"
            if expected_nullable != actual["nullable"]:
                problems.append(
                    f"{table_name}.{col_name} 可空性不符：期望 {expected_nullable} / 实际 {actual['nullable']}"
                )

            checks += 1
            actual_default = None if actual["default"] == "<NULL>" else actual["default"]
            if field["default"] != actual_default:
                problems.append(
                    f"{table_name}.{col_name} 默认值不符：期望 {field['default']!r} / 实际 {actual_default!r}"
                )

            expected_extra = field.get("extra", "")
            checks += 1
            if expected_extra:
                if expected_extra.lower() not in actual["extra"].lower():
                    problems.append(
                        f"{table_name}.{col_name} 额外行为不符：期望包含 {expected_extra!r} / 实际 {actual['extra']!r}"
                    )
            else:
                lowered_extra = actual["extra"].lower()
                if "auto_increment" in lowered_extra or "generated" in lowered_extra:
                    problems.append(
                        f"{table_name}.{col_name} 存在未预期额外行为：{actual['extra']!r}"
                    )

    # 3. 索引（含 PRIMARY 与二级索引）：存在性 / 唯一性 / 列顺序
    live_indexes: dict[str, dict[str, tuple[bool, list[str]]]] = {}
    for row in query_fn(
        "SELECT table_name, index_name, non_unique, "
        "GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') "
        "FROM information_schema.statistics "
        f"WHERE table_schema=DATABASE() AND table_name IN ({table_list_sql}) "
        "GROUP BY table_name, index_name, non_unique"
    ):
        table_name, index_name, non_unique, cols_csv = row
        live_indexes.setdefault(table_name, {})[index_name] = (
            non_unique == "0",
            cols_csv.split(",") if cols_csv else [],
        )

    total_indexes = 0
    for table_name, table_spec in expected_tables.items():
        expected_idxs = table_spec["indexes"]
        total_indexes += len(expected_idxs)
        if table_name not in live_tables:
            continue
        actual_idxs = live_indexes.get(table_name, {})
        expected_idx_names = {idx["name"] for idx in expected_idxs}
        extra_idxs = sorted(set(actual_idxs) - expected_idx_names)
        checks += 1
        if extra_idxs:
            problems.append(f"{table_name} 多出未定义索引：{extra_idxs}")

        for idx in expected_idxs:
            idx_name = idx["name"]
            checks += 1
            actual_idx = actual_idxs.get(idx_name)
            if actual_idx is None:
                problems.append(f"{table_name} 缺索引：{idx_name}")
                continue
            actual_unique, actual_cols = actual_idx
            checks += 1
            if actual_unique != idx["unique"]:
                problems.append(
                    f"{table_name}.{idx_name} 唯一性不符：期望 unique={idx['unique']} / 实际 {actual_unique}"
                )
            checks += 1
            if actual_cols != idx["columns"]:
                problems.append(
                    f"{table_name}.{idx_name} 列顺序不符：期望 {idx['columns']} / 实际 {actual_cols}"
                )

    # 4. CHECK 约束
    live_checks: dict[str, set[str]] = {}
    for row in query_fn(
        "SELECT table_name, constraint_name "
        "FROM information_schema.table_constraints "
        "WHERE table_schema=DATABASE() AND constraint_type='CHECK' "
        f"AND table_name IN ({table_list_sql})"
    ):
        live_checks.setdefault(row[0], set()).add(row[1])

    total_checks = 0
    for table_name, table_spec in expected_tables.items():
        expected_chks = set(table_spec.get("checks", []))
        total_checks += len(expected_chks)
        if table_name not in live_tables:
            continue
        actual_chks = live_checks.get(table_name, set())
        checks += 1
        missing_chks = sorted(expected_chks - actual_chks)
        if missing_chks:
            problems.append(f"{table_name} 缺 CHECK 约束：{missing_chks}")
        extra_chks = sorted(actual_chks - expected_chks)
        checks += 1
        if extra_chks:
            problems.append(f"{table_name} 多出 CHECK 约束：{extra_chks}")

    # 5. 物理外键必须为 0
    if spec.get("require_zero_foreign_keys", True):
        fk_count = int(
            query_fn(
                "SELECT COUNT(*) FROM information_schema.table_constraints "
                "WHERE table_schema=DATABASE() AND constraint_type='FOREIGN KEY'"
            )[0][0]
        )
        checks += 1
        if fk_count != 0:
            problems.append(f"存在 {fk_count} 个物理外键（严禁建立 FOREIGN KEY）")

    stats = {
        "tables": len(expected_tables),
        "columns": total_columns,
        "indexes": total_indexes,
        "check_constraints": total_checks,
        "assertions": checks,
        "live_tables": len(live_tables),
    }
    return problems, stats


def build_query_target(args: argparse.Namespace) -> tuple[QueryFn, str]:
    """校验连接参数，返回查询函数与可显示的目标。"""
    if args.host is None and args.port is None and args.user is None:
        service = args.compose_service if args.compose_service is not None else "sc-mysql"
        database = args.database if args.database is not None else "scholarclaw"
        return make_compose_query_fn(service, database), f"Compose {service}/{database}"

    missing = [
        flag
        for flag, value in (
            ("--host", args.host),
            ("--port", args.port),
            ("--database", args.database),
            ("--user", args.user),
        )
        if value is None or str(value).strip() == ""
    ]
    if missing:
        raise SchemaQueryError(
            "TCP 模式需要同时提供 --host、--port、--database 和 --user，"
            f"缺少：{', '.join(missing)}"
        )
    if not 1 <= args.port <= 65535:
        raise SchemaQueryError("--port 必须在 1 到 65535 之间。")
    if args.compose_service is not None:
        raise SchemaQueryError("--compose-service 只能用于 Compose 模式。")
    query = make_tcp_query_fn(args.host, args.port, args.database, args.user)
    return query, f"TCP {args.host}:{args.port}/{args.database}"


def run_cli(
    argv: list[str] | None = None,
    *,
    default_scope: str = "all",
    fixed_scope: str | None = None,
    require_tcp: bool = False,
    query_error_exit_code: int = 2,
    description: str | None = None,
) -> int:
    """统一命令行入口；供主脚本及三个薄兼容入口复用。"""
    parser = argparse.ArgumentParser(description=description or __doc__)
    if fixed_scope is None:
        parser.add_argument(
            "--scope",
            action="append",
            help="校验范围：all, mvp, schedule, planning（可重复传入或逗号分隔，默认 all）",
        )
    parser.add_argument(
        "--spec",
        type=Path,
        default=DEFAULT_SPEC_PATH,
        help="结构规范 JSON 路径（默认 scripts/db/schema_spec.json）",
    )
    parser.add_argument("--quiet", action="store_true", help="只打印结论与不匹配项")
    parser.add_argument(
        "--host",
        required=require_tcp,
        help="MySQL 主机（TCP 模式需提供主机、端口、库名和用户名）",
    )
    parser.add_argument("--port", type=int, required=require_tcp, help="MySQL 端口")
    parser.add_argument(
        "--database",
        required=require_tcp,
        help="目标数据库名（compose 模式默认 scholarclaw）",
    )
    parser.add_argument("--user", required=require_tcp, help="MySQL 用户名（密码使用 MYSQL_PWD）")
    parser.set_defaults(compose_service=None)
    if not require_tcp:
        parser.add_argument(
            "--compose-service",
            help="docker compose MySQL 服务名（默认 sc-mysql）",
        )
    args = parser.parse_args(argv)

    try:
        query_fn, target = build_query_target(args)
        spec = load_spec(args.spec)
        requested = [fixed_scope] if fixed_scope is not None else (args.scope or [default_scope])
        scopes = resolve_scopes(requested, spec)
        problems, stats = verify_database(query_fn, spec, scopes)
    except SchemaQueryError as exc:
        print(str(exc), file=sys.stderr)
        return query_error_exit_code

    scope_label = "+".join(scopes)
    result_label = f"{scope_label}，{target}"
    if not args.quiet:
        print(
            f"[schema:{scope_label}] 规范版本={spec.get('schema_version', 'unknown')} "
            f"| 目标库表数={stats['live_tables']} "
            f"| 核对 {stats['tables']} 表、{stats['columns']} 列、{stats['indexes']} 索引、{stats['check_constraints']} CHECK"
        )
    if problems:
        print(f"结构核对未通过（{result_label}）：{len(problems)} 处不一致（共核对 {stats['assertions']} 项）", file=sys.stderr)
        for item in problems:
            print(f"  - {item}", file=sys.stderr)
        return 1

    print(
        f"结构核对通过（{result_label}）：{stats['tables']} 表、{stats['columns']} 列、"
        f"{stats['indexes']} 索引、{stats['check_constraints']} 项 CHECK、共 {stats['assertions']} 项断言全部一致。"
    )
    return 0


def main() -> int:
    return run_cli()


if __name__ == "__main__":
    raise SystemExit(main())
