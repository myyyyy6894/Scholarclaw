#!/usr/bin/env python3
"""把真实 MySQL 结构与 `docs/03_数据库设计文档.md` 逐项比对（`06` TASK-201 的「结构核对记录」）。

比对三个维度，全部取自文档而非迁移脚本本身（拿脚本跟脚本比是自证循环）：

1. **表集合**：文档 §1.4 的 7 张业务核心表 + 2 张删除恢复辅助表必须全部存在；§4.8/§1.4 的 2 张 `v1.1` 扩展表必须**不存在**。
2. **字段**：§4.1~§4.7 数据字典的「字段名 / 数据类型 / 是否可空 / 默认值」逐列比对
   （含 `AUTO_INCREMENT` 与 `GENERATED ... STORED` 的识别）。
3. **索引与约束**：§5.1 索引总表的「名称 / 唯一性 / 列顺序」+ §4.x/§4.9 中出现的 `chk_*` 约束名。

用法（在仓库根目录）：

    python3 scripts/db/verify_mvp_schema.py            # 默认经 docker compose exec 查询
    python3 scripts/db/verify_mvp_schema.py --quiet    # 只打印结论与不匹配项

退出码：0 = 全部一致；1 = 存在不一致（脚本会逐条列出）。**只读**，不修改数据库。
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
DOC = REPO_ROOT / "docs" / "03_数据库设计文档.md"
DB = "scholarclaw"
MIGRATION_TABLE = "sc_schema_migration"

MVP_TABLES = [
    "t_user_config", "t_course_namespace", "t_knowledge_doc", "t_knowledge_parent_chunk",
    "t_ddl_task", "t_chat_message", "t_notification_log",
    # V1.0.5：删除恢复辅助表（P9 复核）
    "t_vector_cleanup_outbox", "t_course_deletion_receipt",
]
V11_TABLES = ["t_ddl_subtask", "t_exam_wrong_book"]


def mysql_rows(sql: str) -> list[list[str]]:
    """在 sc-mysql 容器内执行只读查询，返回制表符分隔的行。"""
    cmd = [
        "docker", "compose", "exec", "-T", "sc-mysql", "sh", "-c",
        'mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" -N -B -D ' + DB + ' -e "' + sql.replace('"', '\\"') + '"',
    ]
    out = subprocess.run(cmd, cwd=REPO_ROOT, capture_output=True, text=True, check=False)
    if out.returncode != 0:
        sys.exit("查询失败：" + (out.stderr or out.stdout).strip())
    return [line.split("\t") for line in out.stdout.splitlines() if line.strip()]


def section_text(text: str, heading_pattern: str) -> str:
    r"""取某个三级/四级标题到下一个小标题之间的正文。

    ⚠️ 不能用 `### 4\.\d+ .*?\(table\)` 这种写法：非贪婪 `.*?` 会从**第一个** 4.x 标题
    一路吃到包含该表名的那一节，把好几张表的字段混在一起（本脚本第一版就踩了这个坑）。
    """
    match = re.search(heading_pattern, text, re.M)
    assert match, f"文档中找不到标题：{heading_pattern}"
    rest = text[match.end():]
    end = re.search(r"^#{3,4} ", rest, re.M)
    return rest[: end.start()] if end else rest


def doc_tables() -> dict[str, list[dict[str, str]]]:
    """解析 §4.1~§4.7 的数据字典表格。"""
    text = DOC.read_text(encoding="utf-8")
    result: dict[str, list[dict[str, str]]] = {}
    for table in MVP_TABLES:
        body = section_text(text, rf"^### 4\.\d+ [^\n]*`{table}`[^\n]*$")
        rows = [line for line in body.splitlines() if line.startswith("|")]
        header = [c.strip() for c in rows[0].strip("|").split("|")]
        assert header[:4] == ["字段名", "数据类型", "是否可空", "默认值"], (table, header[:4])
        fields = []
        for line in rows[2:]:
            cells = [c.strip() for c in line.strip("|").split("|")]
            fields.append({
                "name": cells[0].strip("`"),
                "type": cells[1].strip("`"),
                "nullable": cells[2].strip("`"),
                "default": cells[3].strip("`"),
            })
        result[table] = fields
    return result


def doc_indexes() -> dict[str, list[dict[str, str]]]:
    """解析 §5.1 索引总表（只取名称/唯一性/列顺序）。"""
    text = DOC.read_text(encoding="utf-8")
    body = section_text(text, r"^### 5\.1 [^\n]*$")
    rows = [line for line in body.splitlines() if line.startswith("|")]
    header = [c.strip() for c in rows[0].strip("|").split("|")]
    idx = {name: i for i, name in enumerate(header)}
    result: dict[str, list[dict[str, str]]] = {}
    for line in rows[2:]:
        cells = [c.strip() for c in line.strip("|").split("|")]
        table = cells[idx["所属表名"]].strip("`").split()[0]
        name = cells[idx["索引名称"]].strip("`")
        cols = re.search(r"\(([a-z_,\s]+)\)", cells[idx["索引包含字段及顺序"]])
        result.setdefault(table, []).append({
            "name": name,
            "unique": "UNIQUE" in cells[idx["索引类型"]],
            "columns": [c.strip() for c in cols.group(1).split(",")] if cols else [],
        })
    return result


def doc_check_constraints() -> dict[str, set[str]]:
    """从 §4.x 与 §4.9 中提取 `chk_*` 约束名，按表归组。"""
    text = DOC.read_text(encoding="utf-8")
    result: dict[str, set[str]] = {}
    for table in MVP_TABLES:
        body = section_text(text, rf"^### 4\.\d+ [^\n]*`{table}`[^\n]*$")
        result.setdefault(table, set()).update(re.findall(r"`(chk_[a-z_]+)`", body))
    matrix = section_text(text, r"^### 4\.9 [^\n]*$")
    result.setdefault("__matrix__", set()).update(re.findall(r"`(chk_[a-z_]+)`", matrix))
    return result


def normalize_default(cell: str) -> tuple[str | None, str]:
    """把文档「默认值」单元格归一化为 (期望的 COLUMN_DEFAULT, 期望的额外标记)。"""
    value = cell.strip("`* ")
    if value == "AUTO_INCREMENT":
        return None, "auto_increment"
    if value == "GENERATED STORED":
        return None, "STORED GENERATED"
    if value in {"无", "NULL"}:
        return None, ""
    if value.startswith("CURRENT_TIMESTAMP(3) ON UPDATE"):
        return "CURRENT_TIMESTAMP(3)", "on update CURRENT_TIMESTAMP(3)"
    if value == "CURRENT_TIMESTAMP(3)":
        return "CURRENT_TIMESTAMP(3)", "DEFAULT_GENERATED"
    if value.startswith("无数据库默认值"):
        return None, ""
    if len(value) >= 2 and value.startswith("'") and value.endswith("'"):
        return value[1:-1], ""
    return value, ""


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--quiet", action="store_true", help="只打印结论与不匹配项")
    args = parser.parse_args()

    problems: list[str] = []
    checks = 0

    # ---------- 1. 表集合 ----------
    live_tables = {row[0] for row in mysql_rows(
        "SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE()")}
    for table in MVP_TABLES:
        checks += 1
        if table not in live_tables:
            problems.append(f"缺表：{table}（文档 §1.4 要求 MVP 必建）")
    for table in V11_TABLES:
        checks += 1
        if table in live_tables:
            problems.append(f"多表：{table} 属 v1.1 扩展表，不得在 MVP 阶段创建（03 §1.4 / DB-04）")
    if MIGRATION_TABLE not in live_tables:
        problems.append(f"缺表：{MIGRATION_TABLE}（迁移版本记录表）")
    if not args.quiet:
        print(f"[1/3] 表集合：实测 {len(live_tables)} 张 ← {sorted(live_tables)}")

    # ---------- 2. 字段：名称 / 类型 / 可空 / 默认值 ----------
    live_columns: dict[str, dict[str, dict[str, str]]] = {}
    for table, name, ctype, nullable, default, extra in mysql_rows(
            "SELECT table_name, column_name, column_type, is_nullable, "
            "IFNULL(column_default,'<NULL>'), extra FROM information_schema.columns "
            "WHERE table_schema=DATABASE()"):
        live_columns.setdefault(table, {})[name] = {
            "type": ctype, "nullable": nullable, "default": default, "extra": extra}

    dictionary = doc_tables()
    for table, fields in dictionary.items():
        live = live_columns.get(table, {})
        checks += 1
        missing = [f["name"] for f in fields if f["name"] not in live]
        if missing:
            problems.append(f"{table} 缺字段：{missing}")
            continue
        extra_cols = sorted(set(live) - {f["name"] for f in fields})
        if extra_cols:
            problems.append(f"{table} 多出文档未定义的字段：{extra_cols}")
        for field in fields:
            actual = live[field["name"]]
            checks += 1
            if field["type"].lower() != actual["type"].lower():
                problems.append(f"{table}.{field['name']} 类型不符：文档 {field['type']} / 实际 {actual['type']}")
            checks += 1
            expected_nullable = "YES" if field["nullable"] in {"NULL", "是"} else "NO"
            if expected_nullable != actual["nullable"]:
                problems.append(f"{table}.{field['name']} 可空性不符：文档 {field['nullable']} / 实际 {actual['nullable']}")
            expected_default, expected_extra = normalize_default(field["default"])
            checks += 1
            actual_default = None if actual["default"] == "<NULL>" else actual["default"]
            if expected_default != actual_default:
                problems.append(
                    f"{table}.{field['name']} 默认值不符：文档 {expected_default!r} / 实际 {actual_default!r}")
            if expected_extra:
                checks += 1
                if expected_extra.lower() not in actual["extra"].lower():
                    problems.append(
                        f"{table}.{field['name']} 额外行为不符：期望包含 {expected_extra!r} / 实际 {actual['extra']!r}")
    if not args.quiet:
        print(f"[2/3] 字段：核对 {sum(len(f) for f in dictionary.values())} 列（类型/可空/默认值/生成列）")

    # ---------- 3. 索引与 CHECK 约束 ----------
    live_indexes: dict[str, dict[str, dict]] = {}
    for table, index, non_unique, seq, column in mysql_rows(
            "SELECT table_name, index_name, non_unique, seq_in_index, column_name "
            "FROM information_schema.statistics WHERE table_schema=DATABASE() "
            "AND index_name<>'PRIMARY' ORDER BY table_name, index_name, seq_in_index"):
        entry = live_indexes.setdefault(table, {}).setdefault(
            index, {"unique": non_unique == "0", "columns": []})
        entry["columns"].append(column)

    for table, indexes in doc_indexes().items():
        if table not in MVP_TABLES:
            continue
        for expected in indexes:
            checks += 1
            actual = live_indexes.get(table, {}).get(expected["name"])
            if actual is None:
                problems.append(f"{table} 缺索引：{expected['name']}")
                continue
            if actual["unique"] != expected["unique"]:
                problems.append(f"{table}.{expected['name']} 唯一性不符：文档 unique={expected['unique']}")
            if expected["columns"] and actual["columns"] != expected["columns"]:
                problems.append(
                    f"{table}.{expected['name']} 列顺序不符：文档 {expected['columns']} / 实际 {actual['columns']}")

    live_checks = {row[1] for row in mysql_rows(
        "SELECT table_name, constraint_name FROM information_schema.table_constraints "
        "WHERE table_schema=DATABASE() AND constraint_type='CHECK'")}
    documented_checks: set[str] = set()
    for names in doc_check_constraints().values():
        documented_checks |= names
    for name in sorted(documented_checks):
        checks += 1
        if name not in live_checks:
            problems.append(f"缺 CHECK 约束：{name}（文档 §4.x/§4.9 有定义）")
    if not args.quiet:
        print(f"[3/3] 索引与约束：索引 {sum(len(v) for v in live_indexes.values())} 个、"
              f"CHECK {len(live_checks)} 个（文档 {len(documented_checks)} 个）")

    # ---------- 4. 物理外键必须为 0（03 §3.3.1：引用完整性由服务层 + 领域事件 + GC 保证） ----------
    fk_count = int(mysql_rows(
        "SELECT COUNT(*) FROM information_schema.table_constraints "
        "WHERE table_schema=DATABASE() AND constraint_type='FOREIGN KEY'")[0][0])
    checks += 1
    if fk_count != 0:
        problems.append(f"存在 {fk_count} 个物理外键（03 §3.3.1 明确不建 FOREIGN KEY）")

    print()
    if problems:
        print(f"❌ 结构核对未通过：{len(problems)} 处不一致（共核对 {checks} 项）")
        for item in problems:
            print("   -", item)
        return 1
    print(f"✅ 结构核对通过：{checks} 项全部一致"
          f"（7 张业务核心表 + 2 张删除恢复辅助表；字段类型/可空/默认值/生成列、"
          f"索引列顺序、CHECK 约束；v1.1 扩展表未创建）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
