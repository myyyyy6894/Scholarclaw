#!/usr/bin/env python3
"""Maven 依赖漏洞扫描（OSV 数据库，等价于 osv-scanner 的 Maven 模式）。

为什么不用 OWASP Dependency-Check：本机无 NVD API Key，且 NVD 数据下载受网络限制；
OSV 的 `querybatch` 接口可对 `group:artifact` + 版本批量查询，结果与 osv-scanner 同源。

用法（仓库根目录）：
    python3 scripts/security/scan_maven_osv.py \
        --pom sc-java-backend/pom.xml --output /tmp/maven-osv.json

退出码：0 = 扫描完成（无论是否发现漏洞）；2 = 依赖树/OSV 查询失败。
发现漏洞不会被静默忽略：报告中逐条列出 ID、包、版本、严重性与是否有修复版本。
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import urllib.error
import urllib.request
from collections import Counter
from pathlib import Path

OSV_BATCH = "https://api.osv.dev/v1/querybatch"
OSV_VULN = "https://api.osv.dev/v1/vulns/{id}"
TREE_LINE = re.compile(r"([\w.\-]+):([\w.\-]+):jar:([^:]+):")


def dependency_tree(pom: Path, *, offline: bool) -> str:
    output = Path("/tmp/scholarclaw-maven-tree.txt")
    cmd = ["mvn", "-q", "-f", str(pom), "dependency:tree",
           "-DoutputType=text", f"-DoutputFile={output}"]
    if offline:
        cmd.insert(1, "-o")
    result = subprocess.run(cmd, capture_output=True, text=True, check=False)
    if result.returncode != 0 or not output.is_file():
        raise RuntimeError((result.stderr or result.stdout).strip()[-2000:])
    return output.read_text(encoding="utf-8")


def parse_packages(tree: str) -> list[tuple[str, str, str]]:
    packages = set()
    for line in tree.splitlines():
        match = TREE_LINE.search(line)
        if match:
            packages.add((match.group(1), match.group(2), match.group(3)))
    return sorted(packages)


def post_json(url: str, payload: dict) -> dict:
    request = urllib.request.Request(
        url, data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def get_json(url: str) -> dict:
    with urllib.request.urlopen(url, timeout=20) as response:
        return json.load(response)


def severity_label(osv: dict) -> str:
    scores = osv.get("severity") or []
    if not scores:
        return "UNKNOWN"
    score = str(scores[0].get("score", ""))
    if "VC:H" in score and "VI:H" in score:
        return "HIGH/CRITICAL"
    if "VA:H" in score or "VC:H" in score or "VI:H" in score:
        return "HIGH"
    if "VA:L" in score or "VC:L" in score or "VI:L" in score:
        return "LOW/MEDIUM"
    return "OTHER"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pom", default="sc-java-backend/pom.xml")
    parser.add_argument("--output", default="/tmp/scholarclaw-maven-osv.json")
    parser.add_argument("--offline", action="store_true",
                        help="离线解析依赖树（要求本地仓库已完整）")
    args = parser.parse_args()

    try:
        packages = parse_packages(dependency_tree(Path(args.pom), offline=args.offline))
    except (OSError, RuntimeError, subprocess.SubprocessError) as error:
        print(f"依赖树解析失败：{error}", file=sys.stderr)
        return 2
    if not packages:
        print("依赖树为空", file=sys.stderr)
        return 2

    queries = [{"package": {"name": f"{group}:{artifact}", "ecosystem": "Maven"},
                "version": version} for group, artifact, version in packages]
    try:
        batch = post_json(OSV_BATCH, {"queries": queries})
    except (urllib.error.URLError, TimeoutError, ValueError) as error:
        print(f"OSV 查询失败：{error}", file=sys.stderr)
        return 2

    findings = []
    seen: dict[str, dict] = {}
    for (group, artifact, version), result in zip(packages, batch.get("results", [])):
        for vuln in result.get("vulns", []):
            vuln_id = vuln["id"]
            if vuln_id not in seen:
                try:
                    seen[vuln_id] = get_json(OSV_VULN.format(id=vuln_id))
                except (urllib.error.URLError, TimeoutError, ValueError):
                    seen[vuln_id] = vuln
            detail = seen[vuln_id]
            findings.append({
                "id": vuln_id,
                "package": f"{group}:{artifact}",
                "version": version,
                "severity": severity_label(detail),
                "fix_versions": vuln.get("fix_versions") or [],
                "summary": detail.get("summary") or "",
            })

    report = {
        "packages": len(packages),
        "vulnerabilities": findings,
        "by_package": dict(Counter(
            f"{item['package']}:{item['version']}" for item in findings)),
        "by_severity": dict(Counter(item["severity"] for item in findings)),
        "without_fix": sum(1 for item in findings if not item["fix_versions"]),
    }
    Path(args.output).write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({k: report[k] for k in
                      ("packages", "by_severity", "without_fix")},
                     ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
