"""Read bounded tables from the three supported school export containers."""

import csv
import io
import posixpath
import re
from dataclasses import dataclass
from xml.etree import ElementTree as ET
from zipfile import BadZipFile, ZipFile

MAX_ROWS = 400
MAX_COLUMNS = 64
MAX_BYTES = 2 * 1024 * 1024
MAX_XML_BYTES = 12 * 1024 * 1024
# OOXML built-in clock display formats are referenced by ID, without numFmt entries.
BUILTIN_CLOCKS = {str(number): "h:mm" for number in (18, 19, 20, 21, 22)}
W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
X = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
R = "{http://schemas.openxmlformats.org/officeDocument/2006/relationships}"


@dataclass
class Cell:
    text: str
    width: int = 1
    height: int = 1


@dataclass
class Table:
    label: str
    rows: list[dict[int, Cell]]


def _xml(archive: ZipFile, name: str) -> ET.Element:
    data = archive.read(name)
    # Office parts use UTF-8 or UTF-16. Reject declarations before parsing.
    if re.search(rb"<!\s*(?:DOCTYPE|ENTITY)", data.replace(b"\0", b""), re.I):
        raise ValueError("Office 文件包含不支持的 XML 声明。")
    return ET.fromstring(data)


def _csv(content: bytes) -> list[Table]:
    if b"\0" in content:
        raise ValueError("请选择 UTF-8 或 GB18030 编码的 CSV。")
    try:
        raw = content.decode("utf-8-sig")
    except UnicodeDecodeError:
        raw = content.decode("gb18030")
    reader = csv.reader(io.StringIO(raw, newline=""), strict=True)
    rows = []
    for row in reader:
        if len(rows) >= MAX_ROWS or len(row) > MAX_COLUMNS:
            raise ValueError("CSV 超出支持的行列范围。")
        rows.append({i: Cell(value) for i, value in enumerate(row) if value.strip()})
    return [Table("CSV", rows)]


def _word_text(element: ET.Element) -> str:
    return "".join(
        node.text or "" if node.tag == W + "t" else "\n" if node.tag == W + "br" else "\t"
        for node in element.iter() if node.tag in {W + "t", W + "br", W + "tab"}
    )


def _docx(archive: ZipFile) -> tuple[list[Table], list[str]]:
    body = _xml(archive, "word/document.xml").find(W + "body")
    if body is None:
        raise ValueError("Word 文件缺少正文。")
    tables = []
    notes = []
    for element in body:
        if element.tag == W + "p":
            text = _word_text(element).strip()
            if tables and text.startswith(("备注", "说明", "未排")):
                notes.append(text)
        if element.tag != W + "tbl":
            continue
        rows = []
        merges: dict[int, Cell] = {}
        for row in element.findall(W + "tr"):
            if len(rows) >= MAX_ROWS:
                raise ValueError("Word 表格超出支持的行数。")
            cells = {}
            before = row.find(W + "trPr/" + W + "gridBefore")
            col = int(before.get(W + "val", "0")) if before is not None else 0
            next_merges = {}
            for cell in row.findall(W + "tc"):
                span = cell.find(W + "tcPr/" + W + "gridSpan")
                width = int(span.get(W + "val", "1")) if span is not None else 1
                if col < 0 or width < 1 or col + width > MAX_COLUMNS:
                    raise ValueError("Word 表格超出支持的列数。")
                merge = cell.find(W + "tcPr/" + W + "vMerge")
                if merge is not None and merge.get(W + "val", "continue") == "continue" and col in merges:
                    next_merges[col] = merges[col]
                    next_merges[col].height += 1
                else:
                    text = "\n".join(_word_text(p) for p in cell.findall(W + "p"))
                    cells[col] = Cell(text, width)
                    if merge is not None:
                        next_merges[col] = cells[col]
                col += width
            rows.append(cells)
            merges = next_merges
        tables.append(Table(f"Word 表格 {len(tables) + 1}", rows))
    return tables, notes


def _position(address: str) -> tuple[int, int]:
    match = re.fullmatch(r"([A-Z]{1,3})([1-9][0-9]{0,5})", address)
    if not match:
        raise ValueError("Excel 单元格坐标无效。")
    col = 0
    for char in match[1]:
        col = col * 26 + ord(char) - ord("A") + 1
    return int(match[2]) - 1, col - 1


def _xlsx(archive: ZipFile) -> list[Table]:
    workbook = _xml(archive, "xl/workbook.xml")
    relationships = _xml(archive, "xl/_rels/workbook.xml.rels")
    targets = {
        rel.get("Id"): rel.get("Target", "") for rel in relationships
        if rel.get("TargetMode") != "External" and rel.get("Type", "").endswith("/worksheet")
    }
    strings = []
    if "xl/sharedStrings.xml" in archive.namelist():
        strings = ["".join(node.text or "" for node in item.iter(X + "t"))
                   for item in _xml(archive, "xl/sharedStrings.xml").findall(X + "si")]
    padded_formats = {}
    if "xl/styles.xml" in archive.namelist():
        styles = _xml(archive, "xl/styles.xml")
        formats = BUILTIN_CLOCKS | {
            fmt.get("numFmtId"): fmt.get("formatCode", "") for fmt in styles.findall(X + "numFmts/" + X + "numFmt")
        }
        padded_formats = {i: formats.get(fmt.get("numFmtId"), "")
                          for i, fmt in enumerate(styles.findall(X + "cellXfs/" + X + "xf"))}
    tables = []
    for sheet in workbook.findall(X + "sheets/" + X + "sheet"):
        target = targets.get(sheet.get(R + "id"))
        if not target:
            continue
        path = posixpath.normpath(target.lstrip("/") if target.startswith("/") else "xl/" + target)
        if not path.startswith("xl/worksheets/") or not path.endswith(".xml"):
            raise ValueError("Excel 工作表路径无效。")
        root = _xml(archive, path)
        rows: list[dict[int, Cell]] = []
        for item in root.findall(X + "sheetData/" + X + "row/" + X + "c"):
            kind = item.get("t")
            value = item.findtext(X + "v") or ""
            if kind == "s":
                index = int(value)
                if not 0 <= index < len(strings):
                    raise ValueError("Excel 文本索引无效。")
                value = strings[index]
            elif kind == "inlineStr":
                value = "".join(node.text or "" for node in item.iter(X + "t"))
            elif kind == "e" or (item.find(X + "f") is not None and not value):
                value = "〔Excel 无可用缓存值〕"
            elif kind in {None, "n"} and value:
                fmt = padded_formats.get(int(item.get("s", "0")), "")
                if re.fullmatch(r"0{2,24}", fmt) and value.isdecimal():
                    value = value.zfill(len(fmt))
                elif re.search(r"(?i)h+\s*:\s*m+", fmt) and re.fullmatch(r"0?(?:\.\d+)?", value):
                    seconds = round(float(value or "0") * 86400) % 86400
                    value = f"{seconds // 3600:02}:{seconds // 60 % 60:02}:{seconds % 60:02}"
            if not value.strip():
                continue
            row, col = _position(item.get("r", ""))
            if row >= MAX_ROWS or col >= MAX_COLUMNS:
                raise ValueError("Excel 超出支持的行列范围。")
            while len(rows) <= row:
                rows.append({})
            rows[row][col] = Cell(value)
        for merge in root.findall(X + "mergeCells/" + X + "mergeCell"):
            first, last = merge.get("ref", "").split(":")
            row, col = _position(first)
            end_row, end_col = _position(last)
            if end_row >= MAX_ROWS or end_col >= MAX_COLUMNS or end_col < col or end_row < row:
                raise ValueError("Excel 合并区域超出支持的行列范围。")
            if row < len(rows) and col in rows[row]:
                rows[row][col].width = end_col - col + 1
                rows[row][col].height = end_row - row + 1
        tables.append(Table(f"工作表 {len(tables) + 1}", rows))
    return tables


def read_tables(source_format: str, content: bytes) -> tuple[list[Table], list[str]]:
    if not content or len(content) > MAX_BYTES:
        raise ValueError("请选择不超过 2 MiB 的课表文件。")
    if source_format == "CSV":
        return _csv(content), []
    try:
        with ZipFile(io.BytesIO(content)) as archive:
            entries = archive.infolist()
            names = {item.filename for item in entries}
            if len(entries) > 512 or len(names) != len(entries) or sum(item.file_size for item in entries) > MAX_XML_BYTES:
                raise ValueError("Office 文件解压规模超出限制。")
            if "[Content_Types].xml" not in names or any("vbaproject" in name.lower() for name in names):
                raise ValueError("请选择无宏的学校 XLSX 或 DOCX 导出文件。")
            types = _xml(archive, "[Content_Types].xml")
            part, media = {
                "DOCX": ("/word/document.xml", "wordprocessingml.document.main+xml"),
                "XLSX": ("/xl/workbook.xml", "spreadsheetml.sheet.main+xml"),
            }[source_format]
            if not any(item.get("PartName") == part and item.get("ContentType", "").endswith(media) for item in types):
                raise ValueError("文件内容与扩展名不一致。")
            return _docx(archive) if source_format == "DOCX" else (_xlsx(archive), [])
    except (BadZipFile, ET.ParseError, IndexError, KeyError, RuntimeError, NotImplementedError, OSError) as exc:
        raise ValueError("Office 文件损坏、加密或格式不受支持。") from exc
