#!/usr/bin/env python3
"""워밍업 계열 서버 지표를 누적 카운터·히스토그램 버킷의 차분으로 계산한다.

사용:
  metrics-delta.py collect <출력.json> [--prometheus URL] 앱=포트 ...
  metrics-delta.py diff <이전.json> <이후.json> <출력.json> [--scenario 이름] [--k6-summary 파일] [--label 이름]

collect는 각 앱의 /actuator/prometheus를 직접 읽어 누적 시계열(_total/_count/_sum/_bucket)만 저장한다.
diff 규칙: 이후에만 있는 시계열은 사전값 0, 둘 다 있으면 사전값 차감, 이후에 값이 줄었거나(reset)
이전에 있던 시계열이 사라졌으면 오류로 표시하고 종료 코드 2를 돌려준다(값은 0으로 채우지 않는다).
게이지 최대값과 Timer max는 두 시점 사이의 최대를 알 수 없어 이 파일에 넣지 않는다.
"""
import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

CUMULATIVE = ("_total", "_count", "_sum", "_bucket")
# LongTaskTimer(진행 중 작업) 계열은 이름이 _sum·_count로 끝나도 감소하는 게이지라 누적 카운터가 아니다
NOT_CUMULATIVE = re.compile(r"_active_seconds|_active_count|_duration_sum|_duration_count")
LINE = re.compile(r"^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{.*\})?\s+(\S+)")
LABEL = re.compile(r'([a-zA-Z_][a-zA-Z0-9_]*)="((?:[^"\\]|\\.)*)"')
# 차분 결과로 보관할 계열. 나머지는 지표 수가 많아 요약에 필요한 것만 남긴다.
KEEP_PREFIXES = (
    "http_server_requests_seconds_",
    "spring_cloud_gateway_requests_seconds_",
    "hikaricp_connections_acquire_seconds_",
    "hikaricp_connections_timeout_total",
    "jvm_gc_pause_seconds_",
    "jvm_threads_virtual_",
    "queue_",
    "ticketing_",
    "reactor_netty_connection_provider_pending_connections_time_seconds_",
)
# (메트릭 기본 이름, 묶을 라벨). 나머지 라벨은 합산한다.
HISTOGRAMS = (
    ("http_server_requests_seconds", ("uri", "method")),
    ("spring_cloud_gateway_requests_seconds", ("routeId", "httpMethod")),
    ("hikaricp_connections_acquire_seconds", ("pool",)),
    ("ticketing_gateway_stage_seconds", ("stage", "route")),
    ("ticketing_booking_create_stage_seconds", ("stage",)),
    ("reactor_netty_connection_provider_pending_connections_time_seconds", ("name",)),
)


def parse_labels(raw):
    return dict(LABEL.findall(raw)) if raw else {}


def fetch(url, timeout=10):
    with urllib.request.urlopen(url, timeout=timeout) as response:
        return response.read().decode()


def collect(out, prom, targets):
    result = {"collectedAtEpoch": time.time(), "apps": {}, "prometheusLastScrape": None}
    for target in targets:
        name, port = target.split("=")
        started = time.time()
        try:
            text = fetch(f"http://localhost:{port}/actuator/prometheus")
        except (urllib.error.URLError, OSError) as error:
            result["apps"][name] = {"port": int(port), "ok": False, "error": str(error), "series": {}}
            continue
        series = {}
        for line in text.splitlines():
            if line.startswith("#"):
                continue
            match = LINE.match(line)
            if not match or not match.group(1).endswith(CUMULATIVE) or NOT_CUMULATIVE.search(match.group(1)):
                continue
            try:
                value = float(match.group(3))
            except ValueError:
                continue
            if value != value:  # NaN
                continue
            series[match.group(1) + (match.group(2) or "")] = value
        result["apps"][name] = {
            "port": int(port), "ok": True, "series": series,
            "scrapeStartEpoch": started, "scrapeEndEpoch": time.time(),
        }
    if prom:
        try:
            query = urllib.parse.urlencode({"query": 'timestamp(up{job="apps"})'})
            body = json.loads(fetch(f"{prom}/api/v1/query?{query}"))
            result["prometheusLastScrape"] = {
                item["metric"].get("instance", "?"): float(item["value"][1]) for item in body["data"]["result"]
            }
        except (urllib.error.URLError, OSError, KeyError, ValueError):
            result["prometheusLastScrape"] = None
    with open(out, "w") as handle:
        json.dump(result, handle)
    failed = [name for name, app in result["apps"].items() if not app["ok"]]
    if failed:
        print(f"수집 실패: {', '.join(failed)}", file=sys.stderr)
        return 2
    return 0


def quantile(q, buckets):
    """buckets: [(le, 누적 개수)] 정렬. Prometheus histogram_quantile과 같은 선형 보간."""
    if not buckets or buckets[-1][1] <= 0:
        return None
    rank = q * buckets[-1][1]
    previous_le, previous_count = 0.0, 0.0
    for le, count in buckets:
        if count >= rank:
            if le == float("inf"):
                return previous_le
            if count == previous_count:
                return le
            return previous_le + (le - previous_le) * (rank - previous_count) / (count - previous_count)
        previous_le, previous_count = le, count
    return None


def summarize(deltas):
    """deltas: {앱: {시계열 키: 차분}} -> 앱별 히스토그램 요약."""
    summary = {}
    for app, series in deltas.items():
        groups = {}
        for key, delta in series.items():
            match = LINE.match(f"{key} 0")
            name, labels = match.group(1), parse_labels(match.group(2))
            for base, group_labels in HISTOGRAMS:
                if not name.startswith(base + "_"):
                    continue
                suffix = name[len(base) + 1:]
                if suffix not in ("bucket", "count", "sum"):
                    continue
                gkey = (base, tuple((label, labels.get(label, "")) for label in group_labels))
                entry = groups.setdefault(gkey, {"buckets": {}, "count": 0.0, "sum": 0.0})
                if suffix == "bucket":
                    le = float("inf") if labels["le"] == "+Inf" else float(labels["le"])
                    entry["buckets"][le] = entry["buckets"].get(le, 0.0) + delta
                else:
                    entry[suffix] += delta
        rows = []
        for (base, label_pairs), entry in sorted(groups.items()):
            if entry["count"] <= 0:
                continue
            buckets = sorted(entry["buckets"].items())
            rows.append({
                "metric": base, **dict(label_pairs), "count": round(entry["count"]),
                "avgSeconds": entry["sum"] / entry["count"],
                "p50Seconds": quantile(0.5, buckets), "p95Seconds": quantile(0.95, buckets),
                "p99Seconds": quantile(0.99, buckets), "bucketsAvailable": bool(buckets),
            })
        summary[app] = rows
    return summary


def route_count(summary, app, base, **labels):
    total, found = 0, False
    for row in summary.get(app, []):
        if row["metric"] == base and all(row.get(k) == v for k, v in labels.items()):
            total += row["count"]
            found = True
    return total if found else None


def check(name, expected, observed, op="eq"):
    ok = None if observed is None or expected is None else (observed == expected if op == "eq" else observed >= expected)
    return {"name": name, "expectedFromK6": expected, "observedServerDelta": observed, "op": op, "ok": ok}


def diff(pre_path, post_path, out, scenario, k6_summary, label):
    pre, post = json.load(open(pre_path)), json.load(open(post_path))
    errors, deltas = [], {}
    for app, post_app in post["apps"].items():
        pre_app = pre["apps"].get(app)
        if not post_app["ok"] or not pre_app or not pre_app["ok"]:
            errors.append({"app": app, "type": "collect_failed"})
            continue
        for key, before in pre_app["series"].items():
            if key not in post_app["series"]:
                errors.append({"app": app, "type": "series_missing", "series": key})
        app_delta = {}
        for key, after in post_app["series"].items():
            before = pre_app["series"].get(key, 0.0)  # 처음 생긴 시계열의 사전값은 0
            if after < before:
                errors.append({"app": app, "type": "reset", "series": key, "before": before, "after": after})
                continue
            if after > before and key.startswith(KEEP_PREFIXES):
                app_delta[key] = after - before
        deltas[app] = app_delta
    summary = summarize(deltas)

    def total(app, prefix, extra=None):
        return sum(v for k, v in deltas.get(app, {}).items() if k.startswith(prefix) and (extra is None or extra in k))

    gc = {app: {"pauseCount": total(app, "jvm_gc_pause_seconds_count"), "pauseSeconds": total(app, "jvm_gc_pause_seconds_sum")} for app in deltas}
    hikari = {}
    for app in deltas:
        count, seconds = total(app, "hikaricp_connections_acquire_seconds_count"), total(app, "hikaricp_connections_acquire_seconds_sum")
        if count or seconds:
            hikari[app] = {"acquireCount": count, "acquireAvgSeconds": seconds / count if count else None,
                           "timeouts": total(app, "hikaricp_connections_timeout_total")}
    counters = {app: {k: v for k, v in series.items() if k.startswith(("queue_", "ticketing_")) and k.split("{")[0].endswith(("_total", "_count"))}
                for app, series in deltas.items()}

    checks = []
    if k6_summary:
        metrics = json.load(open(k6_summary))["metrics"]
        value = lambda name: (metrics.get(name) or {}).get("count")  # noqa: E731 (k6 export의 Counter는 count)
        if scenario == "smoke":
            checks.append(check("k6 http_reqs = core GET /api/v1/performances", value("http_reqs"),
                                route_count(summary, "core", "http_server_requests_seconds", uri="/api/v1/performances", method="GET")))
        else:
            attempts = value("flow_booking_attempt")
            op = "eq" if scenario == "hot-seats" else "ge"  # A는 409 재시도가 있어 서버 요청이 시도 수 이상
            checks.append(check("k6 예매 시도 = Gateway core-bookings-create POST", attempts,
                                route_count(summary, "gateway", "spring_cloud_gateway_requests_seconds", routeId="core-bookings-create", httpMethod="POST"), op))
            checks.append(check("k6 예매 시도 = core POST /api/v1/bookings", attempts,
                                route_count(summary, "core", "http_server_requests_seconds", uri="/api/v1/bookings", method="POST"), op))
    result = {
        "label": label, "scenario": scenario,
        "preCollectedAtEpoch": pre["collectedAtEpoch"], "postCollectedAtEpoch": post["collectedAtEpoch"],
        "prometheusLastScrapePre": pre.get("prometheusLastScrape"), "prometheusLastScrapePost": post.get("prometheusLastScrape"),
        "errors": errors, "valid": not errors, "controlledInputChecks": checks,
        "histograms": summary, "gcDelta": gc, "hikariDelta": hikari, "counterDeltas": counters,
        "note": "누적 카운터·히스토그램 버킷의 직접 수집 차분이다. 게이지 최대값(Hikari pending 등)과 Timer max는 두 시점 사이 최대를 알 수 없어 포함하지 않으며 Prometheus 5초 표본(prometheus.json)의 시간 창 한계를 따른다.",
    }
    with open(out, "w") as handle:
        json.dump(result, handle, indent=1)
    bad = [c for c in checks if c["ok"] is False]
    for c in checks:
        print(f"통제 입력 {'일치' if c['ok'] else ('불일치' if c['ok'] is False else '판정 불가')}: {c['name']} k6={c['expectedFromK6']} 서버={c['observedServerDelta']}")
    if errors:
        print(f"차분 오류 {len(errors)}건(reset·누락·수집 실패). {out} 참고", file=sys.stderr)
        return 2
    return 1 if bad else 0


def main(argv):
    if len(argv) >= 3 and argv[0] == "collect":
        prom, rest = None, argv[2:]
        if rest and rest[0] == "--prometheus":
            prom, rest = rest[1], rest[2:]
        return collect(argv[1], prom, rest)
    if len(argv) >= 4 and argv[0] == "diff":
        options = {"--scenario": None, "--k6-summary": None, "--label": ""}
        rest = argv[4:]
        while rest:
            options[rest[0]] = rest[1]
            rest = rest[2:]
        return diff(argv[1], argv[2], argv[3], options["--scenario"], options["--k6-summary"], options["--label"])
    print(__doc__, file=sys.stderr)
    return 64


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
