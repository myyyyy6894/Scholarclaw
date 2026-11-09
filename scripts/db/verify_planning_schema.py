#!/usr/bin/env python3
"""只读核对 V1.0.12–V1.0.13 规划结构；必须明确目标库，密码用 MySQL 标准环境变量。"""
from __future__ import annotations

import argparse
import subprocess
import sys

EXPECTED_COLUMNS = {
    "t_planning_preference": {"user_id", "timezone", "windows_json", "focus_minutes", "daily_limit_minutes",
                              "buffer_minutes", "version", "updated_at"},
    "t_personal_event": {"event_id", "user_id", "title", "timezone", "start_date", "end_date", "weekday",
                         "start_minute", "end_minute", "hard", "version", "created_at", "updated_at"},
    "t_task_planning_profile": {"user_id", "task_id", "estimated_minutes", "remaining_minutes", "priority",
                                "earliest_start_utc", "splittable", "min_block_minutes", "version", "updated_at"},
    "t_study_plan": {"plan_id", "user_id", "client_request_id", "request_fingerprint", "from_date", "to_date",
                     "timezone", "source_fingerprint", "occupancy_fingerprint", "state", "revision", "warnings_json",
                     "shortages_json", "unavailable_json", "accept_request_id", "accept_fingerprint", "created_at", "updated_at"},
    "t_study_plan_block": {"block_id", "plan_id", "user_id", "task_id", "task_title", "start_utc", "end_utc", "local_date",
                           "state", "locked", "overdue", "version", "created_at", "updated_at"},
}
EXPECTED_INDEXES = {("t_planning_preference", "PRIMARY", "user_id"),
                    ("t_personal_event", "PRIMARY", "event_id"),
                    ("t_personal_event", "idx_personal_event_user", "user_id,event_id"),
                    ("t_task_planning_profile", "PRIMARY", "user_id,task_id"),
                    ("t_study_plan", "PRIMARY", "plan_id"),
                    ("t_study_plan", "uk_study_plan_user_request", "user_id,client_request_id"),
                    ("t_study_plan", "uk_study_plan_user_accept", "user_id,accept_request_id"),
                    ("t_study_plan", "idx_study_plan_user_state", "user_id,state,plan_id"),
                    ("t_study_plan_block", "PRIMARY", "block_id"),
                    ("t_study_plan_block", "idx_study_block_plan", "user_id,plan_id,start_utc,block_id"),
                    ("t_study_plan_block", "idx_study_block_state", "user_id,state,start_utc,end_utc"),
                    ("t_study_plan_block", "idx_study_block_task", "user_id,task_id,state")}
EXPECTED_CHECKS = {"chk_planning_preference", "chk_personal_event", "chk_task_planning_profile", "chk_study_plan", "chk_study_block"}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    for arg in ("host", "database", "user"):
        parser.add_argument("--" + arg, required=True)
    parser.add_argument("--port", type=int, required=True)
    args = parser.parse_args()
    cmd = ["mysql", "--no-defaults", "--protocol=TCP", "--connect-timeout=3", "-N", "-B", "-h", args.host,
           "-P", str(args.port), "-u", args.user, "-D", args.database]
    names = ",".join("'" + table + "'" for table in EXPECTED_COLUMNS)

    def rows(sql: str) -> list[list[str]]:
        result = subprocess.run([*cmd, "-e", sql], capture_output=True, text=True, check=False)
        if result.returncode:
            raise RuntimeError("MySQL 结构查询失败，请检查目标和连接权限。")
        return [line.split("\t") for line in result.stdout.splitlines()]

    try:
        columns: dict[str, set[str]] = {name: set() for name in EXPECTED_COLUMNS}
        for table, column in rows("SELECT TABLE_NAME,COLUMN_NAME FROM information_schema.COLUMNS WHERE "
                                  "TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN (" + names + ")"):
            columns[table].add(column)
        indexes = {tuple(row) for row in rows("SELECT TABLE_NAME,INDEX_NAME,GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) "
                                             "FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ("
                                             + names + ") GROUP BY TABLE_NAME,INDEX_NAME")}
        checks = {row[0] for row in rows("SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS WHERE "
                                         "TABLE_SCHEMA=DATABASE() AND CONSTRAINT_TYPE='CHECK' AND TABLE_NAME IN (" + names + ")")}
        if columns != EXPECTED_COLUMNS or indexes != EXPECTED_INDEXES or checks != EXPECTED_CHECKS:
            raise RuntimeError("规划资料表的列、索引或 CHECK 与迁移不一致。")
    except (OSError, RuntimeError) as exc:
        print(str(exc), file=sys.stderr)
        return 1
    print(f"规划资料结构核对通过：{len(columns)}表、{sum(map(len, columns.values()))}列、{len(indexes)}索引、{len(checks)}项CHECK。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
