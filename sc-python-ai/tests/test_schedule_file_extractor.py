"""Equivalent synthetic exports and optional local regression for the private sample."""

import base64
import csv
import io
import os
from pathlib import Path
from xml.sax.saxutils import escape
from zipfile import ZipFile

import pytest

from app.services.schedule.file_extractor import extract_file, parse_weeks

GRID = [
    ["节次", "星期一", "星期二", "星期三"],
    ["1", "", "计算课程 003\n(6~12周) (1-2节) A101 教师甲\n微积分 017\n(2~17周) (1-2节) A102 教师乙", ""],
    ["2", "", "", ""],
    ["3", "", "数据库 002\n(1~8周) (3-4节) B201 教师丙\n(9~12周) (3-4节) B202 教师丙", ""],
    ["4", "", "", ""],
]
DETAIL = [
    ["课程名称", "教学班号", "星期", "节次", "周次", "教室", "教师"],
    ["计算课程", "003", "周二", "1-2", "6~12周", "A101", "教师甲"],
    ["微积分", "017", "周二", "1-2", "2~17周", "A102", "教师乙"],
    ["数据库", "002", "周二", "3-4", "1~8周", "B201", "教师丙"],
    ["数据库", "002", "周二", "3-4", "9~12周", "B202", "教师丙"],
]
W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
X = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"


def _zip(parts: dict[str, str], main: str, kind: str) -> bytes:
    target = io.BytesIO()
    with ZipFile(target, "w") as archive:
        archive.writestr("[Content_Types].xml", f'<Types><Override PartName="/{main}" '
                         f'ContentType="application/vnd.openxmlformats-officedocument.{kind}.main+xml"/></Types>')
        for name, content in parts.items():
            archive.writestr(name, content)
    return target.getvalue()


def _docx(rows: list[list[str]]) -> bytes:
    table = ""
    for i, row in enumerate(rows):
        table += "<w:tr>"
        for j, cell in enumerate(row):
            merge = ""
            if j == 2 and i in {1, 2, 3, 4} and rows is GRID:
                marker = ' w:val="restart"' if i in {1, 3} else ""
                merge = f"<w:tcPr><w:vMerge{marker}/></w:tcPr>"
            lines = "<w:br/>".join(f"<w:t>{escape(line)}</w:t>" for line in cell.splitlines())
            table += f"<w:tc>{merge}<w:p><w:r>{lines}</w:r></w:p></w:tc>"
        table += "</w:tr>"
    main = "word/document.xml"
    return _zip({main: f'<w:document xmlns:w="{W}"><w:body><w:p><w:r><w:t>姓名：测试学生</w:t></w:r></w:p>'
                 f"<w:tbl>{table}</w:tbl></w:body></w:document>"}, main, "wordprocessingml.document")


def _xlsx(rows: list[list[str]]) -> bytes:
    cells = "".join(
        f'<row r="{i}">' + "".join(
            f'<c r="{chr(65 + j)}{i}" t="inlineStr"><is><t>{escape(cell)}</t></is></c>'
            for j, cell in enumerate(row) if cell
        ) + "</row>" for i, row in enumerate(rows, 1)
    )
    merges = '<mergeCells><mergeCell ref="C2:C3"/><mergeCell ref="C4:C5"/></mergeCells>' if rows is GRID else ""
    main = "xl/workbook.xml"
    return _zip({
        main: f'<workbook xmlns="{X}" xmlns:r="{R}"><sheets><sheet name="fixture" r:id="rId1"/></sheets></workbook>',
        "xl/_rels/workbook.xml.rels": f'<Relationships><Relationship Id="rId1" Type="{R}/worksheet" '
        'Target="worksheets/sheet1.xml"/></Relationships>',
        "xl/worksheets/sheet1.xml": f'<worksheet xmlns="{X}"><sheetData>{cells}</sheetData>{merges}</worksheet>',
    }, main, "spreadsheetml.sheet")


def _csv(rows: list[list[str]]) -> bytes:
    target = io.StringIO(newline="")
    csv.writer(target).writerows(rows)
    return target.getvalue().encode("utf-8-sig")


@pytest.mark.parametrize(("kind", "factory", "rows", "layout"), [
    ("DOCX", _docx, GRID, "GRID"), ("XLSX", _xlsx, GRID, "GRID"), ("CSV", _csv, GRID, "GRID"),
    ("DOCX", _docx, DETAIL, "DETAIL"), ("XLSX", _xlsx, DETAIL, "DETAIL"), ("CSV", _csv, DETAIL, "DETAIL"),
])
def test_format_layout_and_arrangements(kind, factory, rows, layout):
    draft = extract_file(kind, factory(rows))
    canonical = extract_file("CSV", _csv(DETAIL))
    fields = ("title", "section_code", "teacher", "room", "weekday", "start_period", "end_period", "weeks")
    assert [[row[field] for field in fields] for row in draft["rows"]] == [
        [row[field] for field in fields] for row in canonical["rows"]
    ]
    assert draft["tables"][0]["layout"] == layout
    assert len({row["segment_id"] for row in draft["rows"]}) == 4
    assert all(row["source_ref"] and "测试学生" not in row["source_text"] for row in draft["rows"])
    assert draft["minimum_weeks"] == 17
    assert set(draft["rows"][0]["weeks"]) & set(draft["rows"][1]["weeks"]) == set(range(6, 13))
    assert not set(draft["rows"][2]["weeks"]) & set(draft["rows"][3]["weeks"])


def test_manual_mapping_and_quoted_multiline():
    content = _csv([["学号", "字段甲", "字段乙", "字段丙"], ["00111", "研究,方法\n实训", "周一", "1周"]])
    assert extract_file("CSV", content)["rows"] == []
    draft = extract_file("CSV", content, table_index=1, header_row=1,
                         mapping={"title": 2, "weekday": 3, "weeks": 4})
    [row] = draft["rows"]
    assert row["title"] == "研究,方法\n实训"
    assert row["weeks"] == [1]
    assert "00111" not in row["source_text"]


def test_file_type_and_range_validation():
    with pytest.raises(ValueError):
        extract_file("XLSX", _docx(GRID))
    with pytest.raises(ValueError):
        extract_file("DOCX", b"broken zip")
    with pytest.raises(ValueError):
        extract_file("CSV", _csv([["a"]] * 401))
    assert parse_weeks("1~3(单),4周") == [1, 3, 4]


def test_internal_endpoint(authorized_remote_client, remote_client):
    path = "/internal/ai/v1/schedule/extract-file"
    body = {"user_id": "10001", "source_format": "CSV", "content": base64.b64encode(_csv(DETAIL)).decode()}
    assert remote_client.post(path, json=body).status_code == 403
    assert authorized_remote_client.post(path, json={**body, "user_id": "10002"}).status_code == 400
    result = authorized_remote_client.post(path, json=body)
    assert result.status_code == 200
    assert len(result.json()["data"]["rows"]) == 4


def test_local_school_docx():
    path = os.environ.get("SC_LOCAL_SCHEDULE_SAMPLE")
    if not path:
        pytest.skip("Private fixture is opt-in and kept outside version control")
    draft = extract_file("DOCX", Path(path).read_bytes())
    rows = [row for row in draft["rows"] if row["state"] == "COURSE"]
    assert len({row["source_ref"] for row in rows}) == 20
    assert len(rows) == 25 and draft["minimum_weeks"] == 17
    a, b = [row for row in rows if row["weekday"] == 2 and row["start_period"] == 1]
    assert a["title"] != b["title"]
    assert sorted(set(a["weeks"]) & set(b["weeks"])) == list(range(6, 13))
    assert 17 in b["weeks"]
    a, b = [row for row in rows if row["weekday"] == 2 and row["start_period"] == 3]
    assert a["title"] == b["title"] and a["section_code"] == b["section_code"]
    assert a["room"] != b["room"] and not set(a["weeks"]) & set(b["weeks"])
    a, b = [row for row in rows if row["weekday"] == 5 and row["start_period"] == 1]
    assert a["weeks"] == [5, 6, 8, 9, 10, 11, 12] and b["weeks"] == [2]
    assert a["teacher"] != b["teacher"] and a["room"] != b["room"]
    assert [row["weeks"] for row in rows if row["weekday"] == 3 and row["start_period"] == 10] == [[1, 3, 4]]
    assert [row["state"] for row in draft["rows"]].count("UNSCHEDULED") == 1


def test_assist_sends_one_whitelisted_batch(authorized_remote_client, monkeypatch):
    import json

    from app.services.schedule import file_assist

    calls = []

    class Gateway:
        def __init__(self, _settings):
            pass

        async def __aenter__(self):
            return self

        async def __aexit__(self, *args):
            pass

        async def post_json(self, endpoint, route, payload, *, retry):
            calls.append((endpoint, route, payload, retry))
            return {"choices": [{"finish_reason": "stop", "message": {"content": payload["messages"][1]["content"]}}]}

    monkeypatch.setattr(file_assist, "SsrfSafeModelGateway", Gateway)
    from app.schemas.schedule_extract import ScheduleModelFragment

    rows = extract_file("CSV", _csv(DETAIL))["rows"]
    fragments = [{key: row[key] for key in ScheduleModelFragment.model_fields} for row in rows]
    response = authorized_remote_client.post("/internal/ai/v1/schedule/assist-file", json={
        "user_id": "10001", "rows": fragments,
        "chat_config": {"provider": "OLLAMA", "base_url": "http://localhost:11434", "model_name": "fixture"},
    })
    assert response.status_code == 200
    assert len(calls) == 1
    assert calls[0][1] == "chat/completions"
    assert "source_text" not in calls[0][2]["messages"][1]["content"]
    assert json.loads(calls[0][2]["messages"][1]["content"])["rows"] == fragments


def test_xlsx_cached_formula_clock_and_formatted_section_code():
    main = "xl/workbook.xml"
    labels = ["课程", "星期", "周次", "开始时间", "结束时间", "教学班号"]
    headers = "".join(f'<c r="{letter}1" t="inlineStr"><is><t>{value}</t></is></c>'
                      for letter, value in zip("ABCDEF", labels))
    content = _zip({
        main: f'<workbook xmlns="{X}" xmlns:r="{R}"><sheets><sheet r:id="rId1"/></sheets></workbook>',
        "xl/_rels/workbook.xml.rels": f'<Relationships><Relationship Id="rId1" Type="{R}/worksheet" '
        'Target="worksheets/sheet1.xml"/></Relationships>',
        "xl/styles.xml": f'<styleSheet xmlns="{X}"><numFmts><numFmt numFmtId="164" formatCode="000"/></numFmts>'
        '<cellXfs><xf numFmtId="0"/><xf numFmtId="20"/><xf numFmtId="164"/></cellXfs></styleSheet>',
        "xl/worksheets/sheet1.xml": f'<worksheet xmlns="{X}"><sheetData><row>{headers}</row><row>'
        '<c r="A2" t="inlineStr"><is><t>测试课程</t></is></c><c r="B2"><v>2</v></c><c r="C2"><v>1</v></c>'
        '<c r="D2" s="1"><f>TIME(8,0,0)</f><v>0.333333333333</v></c>'
        '<c r="E2" s="1"><v>0.375</v></c><c r="F2" s="2"><v>3</v></c>'
        '</row></sheetData></worksheet>',
    }, main, "spreadsheetml.sheet")
    [row] = extract_file("XLSX", content)["rows"]
    assert (row["start_time"], row["end_time"], row["section_code"]) == ("08:00:00", "09:00:00", "003")
