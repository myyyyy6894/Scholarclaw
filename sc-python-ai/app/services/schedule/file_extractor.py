"""Normalize recognized table layouts into one provenance-bearing schedule draft."""

import re
import unicodedata

from app.services.schedule.office_tables import Cell, Table, read_tables

DAYS = "一二三四五六日"
ALIASES = {
    "title": ("课程", "课程名称", "课名", "科目", "科目名称", "course", "title"),
    "section_code": ("教学班", "教学班号", "班号", "课序号", "section", "section_code"),
    "teacher": ("教师", "任课教师", "授课教师", "老师", "teacher"),
    "room": ("教室", "地点", "上课地点", "上课教室", "room", "location"),
    "weekday": ("星期", "上课星期", "周几", "星期几", "weekday"),
    "weeks": ("周次", "上课周次", "教学周", "周", "weeks"),
    "period": ("节次", "上课节次", "节数", "period"),
    "start_period": ("开始节次", "起始节次", "start_period"),
    "end_period": ("结束节次", "end_period"),
    "start_time": ("开始时间", "上课时间", "start_time"),
    "end_time": ("结束时间", "下课时间", "end_time"),
}
SEPARATOR = r"[-~—–至]"
WEEK_GROUP = re.compile(r"\(([\d,、，\s~\-—–至单双周()]+周)\)")
WEEK_AT_START = re.compile(r"[\d,、，\s~\-—–至单双周()]+周")
SECTION_AT_END = re.compile(r"^(.+?)\s+([0-9]{1,24})$")
PERIOD_PREFIX = re.compile(r"^\s*\(?\s*第?\s*(\d{1,2})(?:\s*" + SEPARATOR + r"\s*(\d{1,2}))?\s*节\s*\)?\s*")


def _norm(value: str) -> str:
    return unicodedata.normalize("NFKC", value).strip()


def _weekday(value: str) -> int | None:
    value = _norm(value).lower()
    chinese = re.fullmatch(r"(?:星期|周)?([一二三四五六日天])", value)
    if chinese:
        return DAYS.index(chinese[1].replace("天", "日")) + 1
    if re.fullmatch(r"[1-7]", value):
        return int(value)
    names = ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"]
    return next((i for i, day in enumerate(names, 1) if value in {day, day[:3]}), None)


def parse_weeks(value: str) -> list[int]:
    weeks = set()
    for piece in re.split(r"[,、;]", _norm(value)):
        piece = re.sub(r"\s+|第|周", "", piece)
        match = re.fullmatch(r"(\d{1,2})(?:" + SEPARATOR + r"(\d{1,2}))?(?:\(?([单双])\)?)?", piece)
        if not match:
            return []
        start, end = int(match[1]), int(match[2] or match[1])
        if not 1 <= start <= end <= 53:
            return []
        parity = match[3]
        weeks.update(week for week in range(start, end + 1) if parity is None or week % 2 == (parity == "单"))
    return sorted(weeks)


def _periods(value: str) -> tuple[int | None, int | None]:
    match = re.fullmatch(r"第?\s*(\d{1,2})(?:\s*" + SEPARATOR + r"\s*(\d{1,2}))?\s*节?", _norm(value))
    if match:
        start, end = int(match[1]), int(match[2] or match[1])
        if 1 <= start <= end <= 30:
            return start, end
    return None, None


def _clock(value: str) -> str | None:
    match = re.fullmatch(r"(\d{1,2}):(\d{2})(?::(\d{2}))?", _norm(value))
    if match:
        hour, minute, second = int(match[1]), int(match[2]), int(match[3] or "0")
        if hour < 24 and minute < 60 and second < 60:
            return f"{hour:02}:{minute:02}:{second:02}"
    return None


def _row(ref: str, segment: int, text: str, *, title=None, section_code=None, teacher=None, room=None,
         weekday=None, periods=(None, None), clocks=(None, None), weeks=None, issues=(), state="COURSE") -> dict:
    issues = list(issues)
    start, end = periods
    time_start, time_end = clocks
    if not time_start or not time_end or time_start >= time_end:
        if time_start or time_end:
            issues.append("起止钟点不完整或无效")
        time_start = time_end = None
    fields = [(title, 200), (section_code, 64), (teacher, 100), (room, 200)]
    if any(value and len(value) > limit for value, limit in fields):
        issues.append("字段过长，请对照原文补齐")
    title, section_code, teacher, room = [value if value and len(value) <= limit else None for value, limit in fields]
    if state == "COURSE":
        for present, issue in [(title, "缺课程名"), (weekday, "缺星期"), (weeks, "缺有效教学周"),
                               (start or time_start, "缺节次或钟点")]:
            if not present:
                issues.append(issue)
        if start and time_start:
            issues.append("同时有节次和钟点，请核对节次表")
    return dict(source_ref=ref, segment_id=f"{ref}#{segment}", source_text=text[:2000], state=state,
                title=title, section_code=section_code, teacher=teacher, room=room, weekday=weekday,
                start_period=start, end_period=end, start_time=time_start, end_time=time_end,
                weeks=weeks or [], issues=issues)


def _detail(table: Table, header: int, columns: dict[str, int]) -> list[dict]:
    result = []
    for i, cells in enumerate(table.rows[header + 1:], header + 2):
        values = {key: cells[column].text.strip() for key, column in columns.items() if column in cells and cells[column].text.strip()}
        if not values:
            continue
        if values.get("title", "").startswith(("备注", "说明", "未排")) and set(values) == {"title"}:
            result.append(_row(f"{table.label} R{i}", 1, values["title"], state="UNSCHEDULED"))
            continue
        periods = _periods(values.get("period", ""))
        if "start_period" in values or "end_period" in values:
            periods = _periods(f"{values.get('start_period', '')}-{values.get('end_period', '')}")
        clocks = (_clock(values.get("start_time", "")), _clock(values.get("end_time", "")))
        result.append(_row(f"{table.label} R{i}", 1, "\n".join(f"{ALIASES[key][0]}：{val}" for key, val in values.items()),
                           title=values.get("title"), section_code=values.get("section_code"),
                           teacher=values.get("teacher"), room=values.get("room"),
                           weekday=_weekday(values.get("weekday", "")), periods=periods, clocks=clocks,
                           weeks=parse_weeks(values.get("weeks", ""))))
    return result


def _split_grid_cell(text: str, weekday: int, periods: tuple, ref: str) -> list[dict]:
    # Preserve original line breaks for evidence. Structural matches are applied per line.
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    chunks: list[tuple[str, str, str, str]] = []
    pending: list[str] = []
    for original in lines:
        line = _norm(original)
        match = WEEK_GROUP.search(line) or WEEK_AT_START.match(line)
        if match is None:
            pending.append(original)
            continue
        week_text = match[1] if match.re is WEEK_GROUP else match[0]
        prefix = line[:match.start()].strip()
        title = " ".join([*pending, prefix]).strip()
        evidence = "\n".join([*pending, original])
        chunks.append((title, week_text, line[match.end():], evidence))
        pending = []
    result = []
    known: dict[str, tuple[str, str | None]] = {}
    for i, (title, week_text, tail, evidence) in enumerate(chunks, 1):
        issues = []
        section = None
        if title:
            code = SECTION_AT_END.fullmatch(title)
            if code:
                title, section = code[1], code[2]
            known[title + "\0" + (section or "")] = (title, section)
        elif len(known) == 1:
            title, section = next(iter(known.values()))
            issues.append("课程名与教学班沿用同格上一段")
        when = PERIOD_PREFIX.match(tail)
        resolved = _periods(f"{when[1]}-{when[2] or when[1]}") if when else periods
        if when and periods[0] is not None and resolved != periods:
            issues.append("节次采用格内明确标注")
        rest = (tail[when.end():] if when else tail).strip()
        tokens = re.split(r"\s+", rest, maxsplit=1) if rest else []
        result.append(_row(ref, i, evidence, title=title, section_code=section,
                           room=tokens[0] if tokens else None, teacher=tokens[1] if len(tokens) == 2 else None,
                           weekday=weekday, periods=resolved, weeks=parse_weeks(week_text), issues=issues))
    if pending or not result:
        evidence = "\n".join(pending) if pending else text
        result.append(_row(ref, len(result) + 1, evidence, weekday=weekday, periods=periods,
                           state="UNPARSED", issues=["请对照原格填写课程安排"]))
    return result


def _grid(table: Table, header: int, days: dict[int, int]) -> list[dict]:
    result = []
    period_columns = [col for col in table.rows[header] if col < min(days)]
    if not period_columns and min(days) > 0:
        period_columns = [min(days) - 1]
    elif max(period_columns, default=-1) < min(days) - 1:
        period_columns.append(min(days) - 1)
    for i, cells in enumerate(table.rows[header + 1:], header + 1):
        if any(cell.text.strip().startswith(("备注", "说明", "未排")) for cell in cells.values()):
            for col, cell in cells.items():
                if cell.text.strip().startswith(("备注", "说明", "未排")):
                    result.append(_row(f"{table.label} R{i + 1}C{col + 1}", 1, cell.text, state="UNSCHEDULED"))
            continue
        periods = next((pair for col in reversed(period_columns) if col in cells
                        and (pair := _periods(cells[col].text))[0] is not None), (None, None))
        for col, weekday in days.items():
            cell: Cell | None = cells.get(col)
            if cell is None or not cell.text.strip():
                continue
            inferred = periods
            if cell.height > 1 and periods[0] is not None:
                inferred = (periods[0], min(30, max(periods[1], periods[0] + cell.height - 1)))
            result.extend(_split_grid_cell(cell.text, weekday, inferred, f"{table.label} R{i + 1}C{col + 1}"))
    return result


def extract_file(source_format: str, content: bytes, table_index: int | None = None,
                 header_row: int | None = None, mapping: dict[str, int] | None = None) -> dict:
    tables, notes = read_tables(source_format, content)
    if not tables or len(tables) > 20 or table_index is not None and not 1 <= table_index <= len(tables):
        raise ValueError("未找到所选表格，或文件包含过多表格。")
    rows, summaries = [], []
    for number, table in enumerate(tables, 1):
        summary = {"index": number, "label": table.label, "layout": "UNKNOWN"}
        summaries.append(summary)
        if table_index is not None and number != table_index:
            continue
        if mapping is not None:
            if table_index is None or header_row is None or not 1 <= header_row < len(table.rows) or "title" not in mapping:
                raise ValueError("请指定有效的表格、标题行和课程名称列。")
            summary["layout"] = "DETAIL"
            rows.extend(_detail(table, header_row - 1, {key: col - 1 for key, col in mapping.items()}))
            continue
        for index, cells in enumerate(table.rows[:20]):
            names = {col: _norm(cell.text).lower().replace(" ", "") for col, cell in cells.items()}
            detail = {key: col for col, name in names.items() for key, aliases in ALIASES.items() if name in aliases}
            if "title" in detail and len(detail) >= 3:
                summary["layout"] = "DETAIL"
                rows.extend(_detail(table, index, detail))
                break
            days = {col: day for col, name in names.items() if name.startswith(("星期", "周")) and (day := _weekday(name))}
            if len(days) >= 3 and len(set(days.values())) == len(days):
                summary["layout"] = "GRID"
                rows.extend(_grid(table, index, days))
                break
    for i, note in enumerate(notes, 1):
        rows.append(_row(f"Word 表后备注 {i}", 1, note, state="UNSCHEDULED"))
    # Avoid retaining unknown table contents, which may include student identity metadata.
    if len(rows) > 200 or sum(len(row["source_text"]) for row in rows) > 80_000:
        raise ValueError("课表超出单次校对范围，请选择一个表格或先拆分文件。")
    return {"rows": rows, "tables": summaries,
            "minimum_weeks": max((week for row in rows for week in row["weeks"]), default=0)}
