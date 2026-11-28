import json
from pathlib import Path


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


if __name__ == "__main__":
    try:
        load_dashboards(Path("observability/grafana/dashboards"))
    except (OSError, ValueError) as error:
        raise SystemExit(str(error)) from error
    print("관측 대시보드 검사를 통과했다.")
