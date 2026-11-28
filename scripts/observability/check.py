import json
import re
from hashlib import sha256
from pathlib import Path
from urllib.parse import unquote, urlsplit


def load_dashboards(directory: Path) -> dict[str, dict]:
    dashboards = {}
    for path in sorted(directory.glob("*.json")):
        dashboard = json.loads(path.read_text(encoding="utf-8"))
        uid = dashboard.get("uid")
        if not isinstance(uid, str) or not uid:
            raise ValueError(f"{path}에 대시보드 uid가 없다.")
        if uid in dashboards:
            raise ValueError(f"{path}의 대시보드 uid가 중복된다. ({uid})")
        dashboards[uid] = dashboard
    if not dashboards:
        raise ValueError(f"{directory}에서 대시보드를 찾을 수 없다.")
    return dashboards


def iter_panels(panels: list[dict]):
    for panel in panels:
        yield panel
        yield from iter_panels(panel.get("panels", []))


def iter_alerts():
    for path in sorted(Path("observability/prometheus/rules").glob("*.rules.json")):
        for group in json.loads(path.read_text(encoding="utf-8"))["groups"]:
            for rule in group["rules"]:
                if "alert" in rule:
                    yield rule


def check_dashboards(dashboards: dict[str, dict], alerts: list[dict]):
    for uid in ("ticketing-overview", "ticketing-loadtest", "ticketing-hot-seats-gatling"):
        if uid not in dashboards:
            raise ValueError(f"{uid} 대시보드가 없다.")

    overview_titles = {
        panel["title"] for panel in iter_panels(dashboards["ticketing-overview"]["panels"])
    }
    for rule in alerts:
        panel = rule.get("annotations", {}).get("panel")
        if panel not in overview_titles:
            raise ValueError(f'{rule["alert"]} 알림에 대응하는 운영 패널이 없다. ({panel})')

    loadtest_queries = [
        [panel["title"], [target["expr"] for target in panel.get("targets", []) if "expr" in target]]
        for panel in iter_panels(dashboards["ticketing-loadtest"]["panels"])
    ]
    queries_json = json.dumps(loadtest_queries, ensure_ascii=False, separators=(",", ":"))
    # 운영 개편 전 Overview 파일(27b04b0)의 패널 제목과 PromQL 목록을 기준으로 삼는다.
    original_digest = "6d9c3fdb46ca742251f6a9367ea0ed2f57f0066bc27deca32f43ed984b435218"
    if sha256(queries_json.encode("utf-8")).hexdigest() != original_digest:
        raise ValueError("Load Test 대시보드의 패널 제목 또는 PromQL 목록이 달라졌다.")


def check_runbook(alerts: list[dict]):
    runbook_path = Path("docs/runbook.md")
    headings = re.findall(r"^#{1,6} +(.+)$", runbook_path.read_text(encoding="utf-8"), re.MULTILINE)
    anchors = {re.sub(r"[^\w -]", "", heading.lower()).replace(" ", "-") for heading in headings}
    for rule in alerts:
        link = rule.get("annotations", {}).get("runbook", "")
        url = urlsplit(link)
        if not url.path.endswith(f"/{runbook_path}") or unquote(url.fragment) not in anchors:
            raise ValueError(f'{rule["alert"]} 알림에 대응하는 런북 절이 없다. ({link})')


if __name__ == "__main__":
    try:
        alerts = list(iter_alerts())
        dashboards = load_dashboards(Path("observability/grafana/dashboards"))
        check_dashboards(dashboards, alerts)
        check_runbook(alerts)
    except (OSError, ValueError, KeyError) as error:
        raise SystemExit(str(error)) from error
    print("관측 대시보드와 알림 런북 앵커 검사를 통과했다.")
