#!/usr/bin/env python3
"""只读核对 V1.0.9–V1.0.11 课表结构；必须显式指定目标库，密码使用 MySQL 标准环境变量。"""
from __future__ import annotations

import argparse
import subprocess
import sys

COLUMNS = {
    "t_schedule_import": {
        "import_id": "bigint", "user_id": "bigint", "term_id": "bigint",
        "client_request_id": "varchar(64)", "input_fingerprint": "char(64)", "source_type": "varchar(8)",
        "source_text": "mediumtext", "source_path": "varchar(512)", "source_media_type": "varchar(32)",
        "model_fingerprint": "char(64)", "cleanup_attempt_at": "datetime(3)",
        "status": "varchar(20)", "schedule_version": "bigint", "draft_json": "json", "error_message": "varchar(255)",
        "attempt": "smallint", "lease_token": "char(36)", "lease_until": "datetime(3)", "version": "bigint",
        "expires_at": "datetime(3)", "confirm_request_id": "varchar(64)", "confirm_fingerprint": "char(64)",
        "receipt_json": "json", "created_at": "datetime(3)", "updated_at": "datetime(3)",
    },
    "t_schedule_term": {
        "term_id": "bigint", "user_id": "bigint", "name": "varchar(100)",
        "first_monday": "date", "week_count": "smallint", "timezone": "varchar(64)",
        "is_active": "tinyint", "active_user_id": "bigint", "version": "bigint",
        "schedule_version": "bigint", "created_at": "datetime(3)", "updated_at": "datetime(3)",
    },
    "t_schedule_period": {
        "term_id": "bigint", "user_id": "bigint", "period_no": "smallint",
        "start_time": "time", "end_time": "time",
    },
    "t_schedule_rule": {
        "rule_id": "bigint", "user_id": "bigint", "term_id": "bigint", "course_id": "bigint",
        "title": "varchar(200)", "teacher": "varchar(100)", "room": "varchar(200)",
        "weekday": "tinyint", "start_time": "time", "end_time": "time", "start_period": "smallint",
        "end_period": "smallint", "weeks_json": "json", "version": "bigint",
        "created_at": "datetime(3)", "updated_at": "datetime(3)",
    },
    "t_schedule_exception": {
        "exception_id": "bigint", "user_id": "bigint", "term_id": "bigint", "rule_id": "bigint",
        "original_date": "date", "kind": "varchar(8)", "target_date": "date", "target_start_time": "time",
        "target_end_time": "time", "target_room": "varchar(200)", "version": "bigint",
        "created_at": "datetime(3)", "updated_at": "datetime(3)",
    },
}
NULLABLE = {
    "t_schedule_import": {"source_text", "source_path", "source_media_type", "model_fingerprint", "cleanup_attempt_at", "draft_json", "error_message",
                          "lease_token", "lease_until", "confirm_request_id", "confirm_fingerprint", "receipt_json"},
    "t_schedule_term": {"active_user_id"},
    "t_schedule_period": set(),
    "t_schedule_rule": {"course_id", "teacher", "room", "start_period", "end_period"},
    "t_schedule_exception": {"target_date", "target_start_time", "target_end_time", "target_room"},
}
INDEXES = {
    "t_schedule_import": {"PRIMARY": (0, "import_id"),
                         "uk_schedule_import_request": (0, "user_id,client_request_id"),
                         "uk_schedule_import_confirm": (0, "user_id,confirm_request_id"),
                         "idx_schedule_import_user_term": (1, "user_id,term_id,import_id"),
                         "idx_schedule_import_recovery": (1, "status,lease_until,import_id"),
                         "idx_schedule_import_expiry": (1, "status,expires_at,import_id"),
                         "idx_schedule_import_image_cleanup": (1, "source_type,status,cleanup_attempt_at,import_id")},
    "t_schedule_term": {"PRIMARY": (0, "term_id"), "uk_schedule_active_user": (0, "active_user_id"),
                        "idx_schedule_term_user": (1, "user_id,term_id")},
    "t_schedule_period": {"PRIMARY": (0, "term_id,period_no"),
                          "idx_schedule_period_user": (1, "user_id,term_id")},
    "t_schedule_rule": {"PRIMARY": (0, "rule_id"), "idx_schedule_rule_term": (1, "user_id,term_id,weekday"),
                        "idx_schedule_rule_course": (1, "user_id,course_id")},
    "t_schedule_exception": {"PRIMARY": (0, "exception_id"),
                             "uk_schedule_exception_occurrence": (0, "rule_id,original_date"),
                             "idx_schedule_exception_user_term": (1, "user_id,term_id,target_date")},
}
CHECKS = {"chk_schedule_import", "chk_schedule_term", "chk_schedule_period", "chk_schedule_rule", "chk_schedule_exception"}
DEFAULTS = {("t_schedule_import", "attempt"): "0", ("t_schedule_import", "version"): "1",
            ("t_schedule_term", "is_active"): "0", ("t_schedule_term", "version"): "1",
            ("t_schedule_term", "schedule_version"): "1", ("t_schedule_rule", "version"): "1",
            ("t_schedule_exception", "version"): "1"}
AUTO_IDS = {("t_schedule_import", "import_id"), ("t_schedule_term", "term_id"), ("t_schedule_rule", "rule_id"),
            ("t_schedule_exception", "exception_id")}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", required=True)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--database", required=True)
    parser.add_argument("--user", required=True)
    args = parser.parse_args()
    cmd = ["mysql", "--no-defaults", "--protocol=TCP", "--connect-timeout=3", "-N", "-B",
           "-h", args.host, "-P", str(args.port), "-u", args.user, "-D", args.database]

    def rows(sql: str) -> list[list[str]]:
        result = subprocess.run([*cmd, "-e", sql], capture_output=True, text=True, check=False)
        if result.returncode:
            raise RuntimeError("MySQL 结构查询失败，请检查目标和连接权限。")
        return [line.split("\t") for line in result.stdout.splitlines()]

    names = ",".join("'" + table + "'" for table in COLUMNS)
    problems: list[str] = []
    try:
        columns = rows("SELECT TABLE_NAME,COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,EXTRA,"
                       "COALESCE(COLUMN_DEFAULT,'<NULL>') "
                       "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN (" + names + ")")
        observed: dict[str, dict[str, str]] = {table: {} for table in COLUMNS}
        for table, column, column_type, nullable, extra, default in columns:
            observed[table][column] = column_type
            if (nullable == "YES") != (column in NULLABLE[table]):
                problems.append(f"{table}.{column} 可空性不匹配")
            if column == "active_user_id" and "STORED GENERATED" not in extra.upper():
                problems.append("active_user_id 应为 STORED GENERATED")
            if (table, column) in AUTO_IDS and "auto_increment" not in extra.lower():
                problems.append(f"{table}.{column} 应为 AUTO_INCREMENT")
            if default != DEFAULTS.get((table, column), "<NULL>"):
                problems.append(f"{table}.{column} 默认值不匹配")
        for table, expected in COLUMNS.items():
            if observed[table] != expected:
                problems.append(f"{table} 字段/类型不匹配：实际={observed[table]} 预期={expected}")
        index_rows = rows("SELECT TABLE_NAME,INDEX_NAME,NON_UNIQUE,"
                          "GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',') "
                          "FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ("
                          + names + ") GROUP BY TABLE_NAME,INDEX_NAME,NON_UNIQUE")
        observed_indexes: dict[str, dict[str, tuple[int, str]]] = {table: {} for table in COLUMNS}
        for table, index, non_unique, columns_string in index_rows:
            observed_indexes[table][index] = (int(non_unique), columns_string)
        for table, expected in INDEXES.items():
            if observed_indexes[table] != expected:
                problems.append(f"{table} 索引/列序不匹配：实际={observed_indexes[table]} 预期={expected}")
        checks = {row[0] for row in rows("SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS "
                                         "WHERE TABLE_SCHEMA=DATABASE() AND CONSTRAINT_TYPE='CHECK' "
                                         "AND TABLE_NAME IN (" + names + ")")}
        if checks != CHECKS:
            problems.append(f"CHECK名称不匹配：实际={sorted(checks)} 预期={sorted(CHECKS)}")
    except (OSError, RuntimeError, ValueError) as exc:
        print(str(exc), file=sys.stderr)
        return 2
    if problems:
        print("\n".join(problems), file=sys.stderr)
        return 1
    print(f"课表结构核对通过：{len(COLUMNS)}表、{sum(map(len, COLUMNS.values()))}列、"
          f"{sum(map(len, INDEXES.values()))}索引、{len(CHECKS)}项CHECK。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
