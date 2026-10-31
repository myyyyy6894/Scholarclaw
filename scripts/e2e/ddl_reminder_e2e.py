#!/usr/bin/env python3
"""TASK-801/P9 复核：DDL 梯度提醒真实栈端到端验证。

覆盖（不依赖单元测试口径）：
1. T-24h：due_at 在 24h 内、6h 外，扫描后生成 `DDL_T24H`；
2. T-6h：due_at 在 6h 内，扫描后生成 `DDL_T6H`；
3. 逾期：due_at 推进到过去，任务转 `OVERDUE` 并生成 `DDL_OVERDUE`；
4. 待确认跟进：两次 `DDL_FOLLOW_UP`，第二次后 `follow_up_at = NULL`，不会出现第三次；
5. 免打扰：非紧急跟进通知在 DND 窗口内初始为 `RATE_LIMITED_DELAYED`；
6. T-6h 在 DND 内仍按紧急处理（不为 `RATE_LIMITED_DELAYED`）；
7. 重复扫描：同一任务同一梯度只产生一条通知。

前置（必须由操作者临时设置并在结束后恢复默认 `.env`）：
    SC_DDL_REMINDER_INITIAL_DELAY_MS=1000
    SC_DDL_REMINDER_INTERVAL_MS=3000
    SC_IM_PUSH_ENABLED=true          # 验证 DND / IM 初始状态
    SC_ONEBOT_BASE_URL 可指向不可达地址或 mock；本脚本不校验真实发送
并重启 `sc-java-backend`。脚本会先检查 `.env` 中的提醒周期，过大会给出明确提示。

用法：
    sc-python-ai/.venv/bin/python scripts/e2e/ddl_reminder_e2e.py \
        --base-url http://127.0.0.1
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
import time
import uuid
from datetime import datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

import httpx

FAILS: list[str] = []
BASE = "http://127.0.0.1"
ROOT = Path(__file__).resolve().parents[2]
TZ = ZoneInfo("Asia/Shanghai")


def env_value(name: str, default: str | None = None) -> str | None:
    """优先读进程环境变量，其次读 `.env`；便于用临时 env 注入短周期提醒参数。"""
    value = os.environ.get(name)
    if value is not None:
        return value
    path = ROOT / ".env"
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            if line.strip().startswith(name + "="):
                return line.strip().split("=", 1)[1]
    return default


DB_PASSWORD = env_value("SC_DB_PASSWORD") or ""


def check(name: str, cond: bool, detail: object = "") -> None:
    print(("PASS " if cond else "FAIL ") + name + ("" if cond else f" :: {detail}"))
    if not cond:
        FAILS.append(name)


def api(client: httpx.Client, method: str, path: str, token: str | None = None, **kw):
    headers = kw.pop("headers", {})
    if token:
        headers["Authorization"] = "Bearer " + token
    response = client.request(method, BASE + path, headers=headers, **kw)
    try:
        body = response.json()
    except Exception:
        body = {"raw": response.text}
    return response.status_code, body, response.text


def data_of(body):
    return body.get("data") if isinstance(body, dict) else None


def mysql(sql: str) -> list[str]:
    proc = subprocess.run(
        ["docker", "compose", "exec", "-T", "-e", f"MYSQL_PWD={DB_PASSWORD}",
         "sc-mysql", "mysql", "--default-character-set=utf8mb4", "-N", "-B",
         "-uscholarclaw", "scholarclaw", "-e", sql],
        cwd=ROOT, capture_output=True, text=True, check=False)
    if proc.returncode != 0:
        raise RuntimeError(proc.stderr.strip())
    return [line for line in proc.stdout.splitlines() if line != ""]


def wait_for(fn, timeout: float = 60.0, interval: float = 1.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        value = fn()
        if value:
            return value
        time.sleep(interval)
    return None


def register(client: httpx.Client) -> dict:
    username = "p9ddl_" + uuid.uuid4().hex[:8]
    password = "Passw0rd123"
    status, body, text = api(client, "POST", "/api/v1/auth/register", json={
        "username": username, "password": password, "timezone": "Asia/Shanghai"})
    if status not in (200, 201):
        raise RuntimeError(f"注册失败 {status} {text}")
    data = data_of(body)
    return {"token": data["access_token"], "user_id": str(data["user_id"]),
            "username": username}


def create_task(client: httpx.Client, token: str, title: str, due: datetime) -> str:
    status, body, text = api(client, "POST", "/api/v1/ddl/tasks", token, json={
        "client_request_id": "req_p9_" + uuid.uuid4().hex,
        "course_name": "P9 提醒联调课程",
        "task_title": title,
        "time_precision": "EXACT_DATETIME",
        "due_date": due.date().isoformat(),
        "due_time": due.strftime("%H:%M:%S"),
        "deliverable_format": "PDF",
        "submission_channel": "学习通",
    })
    if status not in (200, 201):
        raise RuntimeError(f"建任务失败 {status} {text}")
    return str(data_of(body)["task_id"])


def notifications(client: httpx.Client, token: str) -> list[dict]:
    status, body, _ = api(client, "GET", "/api/v1/notifications?size=100", token)
    if status != 200:
        return []
    return data_of(body).get("items", [])


def find_notes(client: httpx.Client, token: str, biz_type: str, task_id: str) -> list[dict]:
    matched = [n for n in notifications(client, token)
               if n.get("biz_type") == biz_type and str(n.get("biz_id")) == str(task_id)]
    return sorted(matched, key=lambda n: int(n.get("notification_id", 0)))


def task_row(task_id: str) -> dict:
    rows = mysql(
        "SELECT status, follow_up_count, IFNULL(DATE_FORMAT(follow_up_at,"
        "'%Y-%m-%d %H:%i:%s'),'NULL'), reminded_flags,"
        "IFNULL(DATE_FORMAT(due_at,'%Y-%m-%d %H:%i:%s'),'NULL') "
        f"FROM t_ddl_task WHERE task_id={int(task_id)}")
    if not rows:
        return {}
    parts = rows[0].split("\t")
    return {"status": parts[0], "follow_up_count": int(parts[1]),
            "follow_up_at": parts[2], "reminded_flags": int(parts[3]),
            "due_at": parts[4]}


def set_preferences(client: httpx.Client, token: str, dnd_start: str, dnd_end: str,
                    im_uid: str | None) -> None:
    payload = {
        "timezone": "Asia/Shanghai",
        "dnd_start_time": dnd_start,
        "dnd_end_time": dnd_end,
        "bound_im_platform": "QQ_ONEBOT" if im_uid else None,
        "bound_im_uid": im_uid,
    }
    status, body, text = api(client, "PUT", "/api/v1/user/preferences", token, json=payload)
    if status != 200:
        raise RuntimeError(f"更新偏好失败 {status} {text}")


def dnd_window(now: datetime) -> tuple[str, str]:
    """构造覆盖当前时刻的 3 小时免打扰窗口。"""
    start = now - timedelta(hours=1)
    end = now + timedelta(hours=2)
    return start.strftime("%H:%M"), end.strftime("%H:%M")


def main() -> int:
    global BASE
    parser = argparse.ArgumentParser(description="DDL gradient reminder real-stack E2E")
    parser.add_argument("--base-url", default=BASE)
    parser.add_argument("--password", default="Passw0rd123")
    args = parser.parse_args()
    BASE = args.base_url.rstrip("/")

    interval_ms = int(env_value("SC_DDL_REMINDER_INTERVAL_MS", "60000") or "60000")
    timeout = max(45.0, interval_ms / 1000.0 * 3 + 30)
    if interval_ms > 10000:
        print(f"WARN .env 中 SC_DDL_REMINDER_INTERVAL_MS={interval_ms}；"
              f"本脚本建议 3000，否则等待时间约 {timeout:.0f}s/次")
    if (env_value("SC_IM_PUSH_ENABLED", "false") or "false").lower() != "true":
        print("WARN SC_IM_PUSH_ENABLED 不是 true；DND/IM 初始状态检查会失败。")

    with httpx.Client(timeout=30, trust_env=False) as client:
        user = register(client)
        token = user["token"]
        check("注册用户", bool(user["user_id"]), user)
        im_uid = "9" + str(int(time.time()))[-8:]
        dnd_start, dnd_end = dnd_window(datetime.now(TZ))
        set_preferences(client, token, dnd_start, dnd_end, im_uid)
        print(f"INFO DND 窗口 {dnd_start} ~ {dnd_end}，绑定 IM {im_uid}")

        now = datetime.now(TZ).replace(microsecond=0)

        # 1. T-24h：due 在 12h 后（24h 内、6h 外）
        t24 = create_task(client, token, "P9 T-24h", now + timedelta(hours=12))
        note = wait_for(lambda: find_notes(client, token, "DDL_T24H", t24), timeout)
        check("T-24h 生成 DDL_T24H", bool(note), note)
        check("T-24h 站内信保底", bool(note) and bool(note[0].get("content")), note)
        time.sleep(interval_ms / 1000.0 * 2 + 2)
        check("T-24h 重复扫描不重复通知",
              len(find_notes(client, token, "DDL_T24H", t24)) == 1,
              find_notes(client, token, "DDL_T24H", t24))

        # 2. T-6h：due 在 5h 后
        t6 = create_task(client, token, "P9 T-6h", now + timedelta(hours=5))
        note6 = wait_for(lambda: find_notes(client, token, "DDL_T6H", t6), timeout)
        check("T-6h 生成 DDL_T6H", bool(note6), note6)
        if note6:
            check("T-6h 紧急通知不被 DND 延迟",
                  note6[0].get("outbound_status") != "RATE_LIMITED_DELAYED", note6[0])
        time.sleep(interval_ms / 1000.0 * 2 + 2)
        check("T-6h 重复扫描不重复通知",
              len(find_notes(client, token, "DDL_T6H", t6)) == 1,
              find_notes(client, token, "DDL_T6H", t6))

        # 3. 逾期：先建未来任务，再推进到过去
        overdue = create_task(client, token, "P9 OVERDUE", now + timedelta(hours=2))
        past = (now - timedelta(minutes=1)).strftime("%Y-%m-%d %H:%M:%S")
        mysql(f"UPDATE t_ddl_task SET due_at='{past}', due_date='{now.date()}', "
              f"due_time='{(now - timedelta(minutes=1)).strftime('%H:%M:%S')}', "
              f"status='ACTIVE', reminded_flags=0, follow_up_at=NULL, "
              f"updated_at=NOW(3) WHERE task_id={int(overdue)}")
        note_overdue = wait_for(
            lambda: find_notes(client, token, "DDL_OVERDUE", overdue), timeout)
        check("逾期生成 DDL_OVERDUE", bool(note_overdue), note_overdue)
        row = task_row(overdue)
        check("逾期后任务状态为 OVERDUE", row.get("status") == "OVERDUE", row)

        # 4. 待确认跟进：两次后 follow_up_at=NULL
        follow = create_task(client, token, "P9 FOLLOW_UP", now + timedelta(hours=30))
        follow_at = (now - timedelta(minutes=1)).strftime("%Y-%m-%d %H:%M:%S")
        mysql(f"UPDATE t_ddl_task SET status='PENDING_TIME', time_precision='VAGUE_OR_MISSING', "
              f"due_at=NULL, due_date=NULL, due_time=NULL, time_inferred=0, "
              f"follow_up_at='{follow_at}', follow_up_count=0, reminded_flags=0, "
              f"updated_at=NOW(3) WHERE task_id={int(follow)}")
        first = wait_for(lambda: find_notes(client, token, "DDL_FOLLOW_UP", follow), timeout)
        check("第 1 次跟进生成 DDL_FOLLOW_UP", bool(first), first)
        row = task_row(follow)
        check("第 1 次跟进后 follow_up_count=1", row.get("follow_up_count") == 1, row)
        check("第 1 次跟进后 follow_up_at 非空", row.get("follow_up_at") != "NULL", row)
        time.sleep(interval_ms / 1000.0 * 2 + 2)
        check("第 1 次跟进重复扫描不重复通知",
              len(find_notes(client, token, "DDL_FOLLOW_UP", follow)) == 1,
              find_notes(client, token, "DDL_FOLLOW_UP", follow))
        # 到期后第 2 次
        mysql(f"UPDATE t_ddl_task SET follow_up_at='{follow_at}', updated_at=NOW(3) "
              f"WHERE task_id={int(follow)}")
        second = wait_for(
            lambda: (lambda notes: notes if len(notes) >= 2 else None)(
                find_notes(client, token, "DDL_FOLLOW_UP", follow)), timeout)
        check("第 2 次跟进生成第二条 DDL_FOLLOW_UP", bool(second), second)
        row = task_row(follow)
        check("第 2 次跟进后 follow_up_count=2", row.get("follow_up_count") == 2, row)
        check("第 2 次跟进后 follow_up_at=NULL", row.get("follow_up_at") == "NULL", row)
        if second:
            check("DND 内非紧急跟进为 RATE_LIMITED_DELAYED",
                  second[-1].get("outbound_status") == "RATE_LIMITED_DELAYED", second[-1])
            check("DND 内跟进站内信仍立即落库", bool(second[-1].get("content")), second[-1])
        time.sleep(interval_ms / 1000.0 * 2 + 2)
        check("第 2 次跟进后不再产生第三次",
              len(find_notes(client, token, "DDL_FOLLOW_UP", follow)) == 2,
              find_notes(client, token, "DDL_FOLLOW_UP", follow))

        # 5. T-6h 在 DND 内提前：due-6h 落在窗口内，仍需立即触发且不延迟
        window_start, _ = dnd_window(now)
        start_dt = datetime.strptime(window_start, "%H:%M").replace(
            year=now.year, month=now.month, day=now.day, tzinfo=TZ)
        if start_dt > now:
            start_dt -= timedelta(days=1)
        due_in_dnd = start_dt + timedelta(hours=6, minutes=30)
        if due_in_dnd <= now:
            due_in_dnd += timedelta(days=1)
        t6_dnd = create_task(client, token, "P9 T-6h DND", due_in_dnd)
        note_dnd = wait_for(lambda: find_notes(client, token, "DDL_T6H", t6_dnd), timeout)
        check("DND 内 T-6h 提前触发", bool(note_dnd), note_dnd)
        if note_dnd:
            check("DND 内 T-6h 仍按紧急处理",
                  note_dnd[0].get("outbound_status") != "RATE_LIMITED_DELAYED",
                  note_dnd[0])

        # 6. 任务状态与提醒位
        row24 = task_row(t24)
        check("T-24h 提醒位已置位", (row24.get("reminded_flags", 0) & 1) == 1, row24)
        row6 = task_row(t6)
        check("T-6h 提醒位已置位", (row6.get("reminded_flags", 0) & 2) == 2, row6)
        row_over = task_row(overdue)
        check("逾期提醒位已置位", (row_over.get("reminded_flags", 0) & 4) == 4, row_over)

        print(f"\n测试用户保留：{user['username']} / user_id={user['user_id']}；"
              "请按 README §5 先 SELECT 再授权清理。")
        print(f"\nDDL 梯度提醒 E2E: {len(FAILS)} failure(s)")
        if FAILS:
            print("FAILED: " + ", ".join(FAILS))
            return 1
        return 0


if __name__ == "__main__":
    raise SystemExit(main())
