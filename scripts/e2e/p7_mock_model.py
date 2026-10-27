"""P7 DDL E2E 本地模型替身（确定性、日期不漂移）。

设计要点：

* **不返回固定的未来日期**。绝对日期一律从通知原文里提取，测试脚本生成什么日期就回什么日期；
  这样 ACTIVE 断言不会因为系统时间流逝而失效。
* 相对日期（下周一/今天）按提示词里的【基准时间】计算；测试脚本对该类用例显式传入
  `reference_time`，因此结果可复现。
* 过去/非法用例保留固定历史日期（它们本来就应当是过去）。
"""

import json
import re
from datetime import datetime, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from zoneinfo import ZoneInfo

USER_ZONE = ZoneInfo("Asia/Shanghai")


def messages_text(body):
    return "\n".join(str(m.get("content", "")) for m in body.get("messages", []) if isinstance(m, dict))


def notice_text(body):
    text = messages_text(body)
    m = re.search(r"<<<NOTICE_START>>>\s*(.*?)\s*<<<NOTICE_END>>>", text, re.S)
    return m.group(1).strip() if m else text


def reference_local(text):
    m = re.search(
        r"【基准时间】\s*(\d{4}-\d{2}-\d{2}T[0-9:.]+(?:[+-]\d{2}:\d{2}|Z))", text)
    if not m:
        return datetime.now(USER_ZONE)
    try:
        parsed = datetime.fromisoformat(m.group(1).replace("Z", "+00:00"))
        return parsed.astimezone(USER_ZONE)
    except ValueError:
        return datetime.now(USER_ZONE)


def notice_date(notice):
    m = re.search(r"(20\d{2})年(\d{1,2})月(\d{1,2})日", notice)
    if m:
        return f"{int(m.group(1)):04d}-{int(m.group(2)):02d}-{int(m.group(3)):02d}"
    m = re.search(r"(20\d{2})-(\d{2})-(\d{2})", notice)
    if m:
        return m.group(0)
    return None


def notice_time(notice):
    m = re.search(r"(\d{1,2})[:：](\d{2})", notice)
    if not m:
        return None
    return f"{int(m.group(1)):02d}:{m.group(2)}:00"


def next_monday(reference):
    days = (7 - reference.weekday()) % 7 or 7
    return (reference + timedelta(days=days)).date().isoformat()


def extracted(notice, reference):
    base = {
        "course_name": "操作系统原理",
        "task_title": "提交实验报告",
        "raw_time_text": None,
        "time_precision": "VAGUE_OR_MISSING",
        "is_relative_time": False,
        "parsed_due_date": None,
        "parsed_due_time": None,
        "deliverable_format": "PDF",
        "submission_channel": "学习通",
        "confidence": 0.96,
        "has_ambiguity": False,
        "ambiguity_reason": None,
    }
    due_date = notice_date(notice)
    due_time = notice_time(notice)
    if "尽快提交" in notice or "下周左右" in notice:
        base.update(raw_time_text="尽快提交" if "尽快提交" in notice else "下周左右",
                    task_title="尽快提交实验报告")
    elif "2月29日" in notice:
        base.update(raw_time_text="2月29日", task_title="提交非法日期报告",
                    time_precision="INVALID_OR_PAST")
    elif "过去时间报告" in notice:
        base.update(raw_time_text="2026年10月1日 22:00", task_title="提交过去时间报告",
                    time_precision="INVALID_OR_PAST",
                    parsed_due_date="2026-10-01", parsed_due_time="22:00:00")
    elif "跨年报告" in notice:
        # 显式 model 日期保持同一年，跨年顺延由 Python `TimeParser` 负责。
        base.update(raw_time_text="1月5日 22:00", task_title="提交跨年报告",
                    time_precision="EXACT_DATETIME",
                    parsed_due_date="2026-01-05", parsed_due_time="22:00:00")
    elif "下周报告" in notice:
        base.update(raw_time_text="下周一 22:00", task_title="提交下周报告",
                    time_precision="EXACT_DATETIME", is_relative_time=True,
                    parsed_due_date=next_monday(reference), parsed_due_time="22:00:00")
    elif "今日报告" in notice:
        base.update(raw_time_text="今天 22:00", task_title="提交今日报告",
                    time_precision="EXACT_DATETIME", is_relative_time=True,
                    parsed_due_date=reference.date().isoformat(), parsed_due_time="22:00:00")
    elif "日期任务" in notice and due_date:
        base.update(raw_time_text=notice, task_title="提交日期任务",
                    time_precision="DATE_ONLY", parsed_due_date=due_date)
    elif ("实验二报告" in notice or "实验报告" in notice) and due_date:
        base.update(raw_time_text=notice[-20:], task_title="提交实验二报告",
                    time_precision="EXACT_DATETIME", parsed_due_date=due_date,
                    parsed_due_time=due_time or "22:00:00")
    return {"intent_confidence": 0.98, "ddl_payload": base}


def classified(text):
    user = text.strip()
    if "低置信修改" in user:
        return {"intent": "TASK_MANAGE", "confidence": 0.70}
    if "模糊" in user or "不确定" in user:
        return {"intent": "CHITCHAT_HELP", "confidence": 0.40}
    if any(k in user for k in ("查询", "列出", "完成", "改为", "修改", "删除", "取消")):
        return {"intent": "TASK_MANAGE", "confidence": 0.92}
    if any(k in user for k in ("知识库", "课件", "两阶段锁")):
        return {"intent": "KB_QA", "confidence": 0.92}
    if any(k in user for k in ("通知", "提交", "实验", "作业", "截止")):
        return {"intent": "DDL_EXTRACT", "confidence": 0.94}
    return {"intent": "CHITCHAT_HELP", "confidence": 0.40}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _json(self, code, payload):
        data = json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        self._json(200, {"data": [{"id": "mock-chat"}, {"id": "mock-embed"}]})

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        body = json.loads(self.rfile.read(length) or b"{}")
        path = self.path.rstrip("/")
        if path.endswith("/embeddings"):
            model = body.get("model", "mock-embed")
            self._json(200, {"data": [{"index": 0, "embedding": [0.1] * 8}], "model": model})
            return
        print("CHAT_CALL", flush=True)
        text = messages_text(body)
        if "待提取通知原文" in text:
            content = json.dumps(
                extracted(notice_text(body), reference_local(text)), ensure_ascii=False)
        elif "待分类消息" in text:
            m = re.search(r"【待分类消息】\s*(.*?)\s*【待分类消息结束】", text, re.S)
            content = json.dumps(classified(m.group(1) if m else text), ensure_ascii=False)
        else:
            content = json.dumps({"message": "ok"}, ensure_ascii=False)
        self._json(200, {"choices": [{"index": 0, "message": {"role": "assistant", "content": content}}]})

    def log_message(self, *args):
        return


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description="P7 DDL E2E local model double")
    parser.add_argument("--port", type=int, default=11434)
    args = parser.parse_args()
    ThreadingHTTPServer(("0.0.0.0", args.port), Handler).serve_forever()
