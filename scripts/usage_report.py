#!/usr/bin/env python3
"""phase 실행 로그(logs/<run>/stepNN-attemptNN.json)의 토큰·비용을 step별로 집계한다.

사용법:
    python3 scripts/usage_report.py <phase> [--run RUN_ID] [--json]

--run을 생략하면 가장 최근 run을 쓴다. 같은 step의 재시도(attempt)는 합산한다.
Claude와 Codex 로그를 모두 지원한다. Input Total은 캐시를 포함한 전체 입력이다.
JSON의 input_tokens는 원래 의미를 유지한다(Claude: 신규 입력, Codex: 전체 입력).
Thinking(thinking_tokens)은 Claude가 보고하는 output_tokens의 세부 항목이다.
Codex 로그는 대응 값을 기록하지 않으므로 N/A다.
Actual models는 시도 로그의 metrics.actual_models(Claude: modelUsage, Codex: 세션 메타데이터)다.
보고되지 않은 값은 표에서 N/A, JSON에서 null이다. 일부 시도라도 누락되면 합계도 null이다.
"""

import argparse
import json
import sys
from pathlib import Path

PHASES_DIR = Path(__file__).parent.parent / "phases"
USAGE_KEYS = (
    "input_tokens", "total_input_tokens", "uncached_input_tokens",
    "cache_creation_input_tokens", "cache_read_input_tokens", "output_tokens", "thinking_tokens",
)


def sum_known(values):
    values = list(values)
    return None if any(value is None for value in values) else sum(values)


def normalize_metrics(data: dict) -> dict:
    metrics = data.get("metrics") or {}
    usage = metrics.get("usage") or {}
    provider = data.get("provider")
    if not provider:
        provider = "codex" if (
            "cached_input_tokens" in usage
            or str(metrics.get("usage_source", "")).startswith("codex.")
        ) else "claude"
    raw_input = usage.get("input_tokens")
    if provider == "codex":
        cached = usage.get("cached_input_tokens")
        total = raw_input
        uncached = (
            total - cached if total is not None and cached is not None and 0 <= cached <= total else None
        )
        cache_write = None  # Codex exec의 usage에는 이 필드가 없다.
        turns = metrics.get("completed_turns")
        thinking = None  # Codex 로그에는 reasoning 토큰을 기록하지 않는다.
    else:
        # Claude의 input_tokens에는 cache creation/read가 포함되지 않는다.
        cached = usage.get("cache_read_input_tokens")
        cache_write = usage.get("cache_creation_input_tokens")
        uncached = raw_input
        total = sum_known((raw_input, cache_write, cached))
        turns = metrics.get("num_turns")
        thinking = usage.get("thinking_tokens")  # 과거 로그에는 없다.
    return {
        "input_tokens": raw_input, "total_input_tokens": total,
        "uncached_input_tokens": uncached, "cache_creation_input_tokens": cache_write,
        "cache_read_input_tokens": cached, "output_tokens": usage.get("output_tokens"),
        "thinking_tokens": thinking,
        "turns": turns, "cost_usd": metrics.get("total_cost_usd"),
        "providers": [provider], "actual_models": metrics.get("actual_models") or (
            [metrics["actual_model"]] if metrics.get("actual_model") else []
        ),
    }


def pick_run(phase_dir: Path, run_id: str | None) -> Path:
    runs = sorted(p for p in (phase_dir / "logs").glob("*") if p.is_dir())
    if run_id:
        run = phase_dir / "logs" / run_id
        if not run.is_dir():
            sys.exit(f"오류: run '{run_id}'가 없습니다.")
        return run
    if not runs:
        sys.exit(f"오류: {phase_dir / 'logs'}에 run이 없습니다.")
    return runs[-1]  # run_id가 UTC 타임스탬프로 시작하므로 정렬 순서 = 시간순


def collect(run_dir: Path) -> list[dict]:
    steps: dict[int, dict] = {}
    for path in sorted(run_dir.glob("step*-attempt*.json")):
        data = json.loads(path.read_text(encoding="utf-8"))
        normalized = normalize_metrics(data)
        row = steps.setdefault(data["step"], {
            "step": data["step"], "attempts": 0, "turns": 0, "cost_usd": 0.0,
            "prompt_bytes": 0, "agent_seconds": 0.0, **{k: 0 for k in USAGE_KEYS},
            "providers": [], "actual_models": [],
        })
        row["attempts"] += 1
        for key in ("turns", "cost_usd", *USAGE_KEYS):
            row[key] = sum_known((row[key], normalized[key]))
        for key in ("providers", "actual_models"):
            row[key] = sorted(set(row[key]) | set(normalized[key]))
        row["prompt_bytes"] = max(row["prompt_bytes"], data.get("prompt_bytes", 0))
        row["agent_seconds"] += data.get("agent_elapsed_seconds", 0.0)
    return [steps[k] for k in sorted(steps)]


def totals(rows: list[dict]) -> dict:
    keys = ("attempts", "turns", "cost_usd", "agent_seconds", *USAGE_KEYS)
    return {k: sum_known(r[k] for r in rows) for k in keys}


def print_table(run_dir: Path, rows: list[dict]) -> None:
    columns = [
        ("step", "Step", 5, "d"),
        ("attempts", "Attempts", 8, "d"),
        ("turns", "Turns", 5, "d"),
        ("total_input_tokens", "Input Total", 11, ",d"),
        ("uncached_input_tokens", "Uncached", 10, ",d"),
        ("cache_creation_input_tokens", "Cache Write", 12, ",d"),
        ("cache_read_input_tokens", "Cache Read", 12, ",d"),
        ("output_tokens", "Output", 10, ",d"),
        ("thinking_tokens", "Thinking", 10, ",d"),
        ("cost_usd", "Cost (USD)", 10, ".3f"),
        ("prompt_bytes", "Prompt (B)", 10, ",d"),
        ("agent_seconds", "Time (s)", 8, ".0f"),
    ]

    gap = "  "

    def format_row(data: dict, total: bool = False) -> str:
        cells = []

        for key, _, width, fmt in columns:
            if total and key == "step":
                value = "Total"
            elif total and key == "prompt_bytes":
                value = ""
            elif data[key] is None:
                value = "N/A"
            else:
                value = format(data[key], fmt)

            cells.append(f"{value:>{width}}")

        return gap.join(cells)

    print(f"Run: {run_dir.name}")

    header = gap.join(
        f"{title:>{width}}"
        for _, title, width, _ in columns
    )

    separator = "-" * len(header)

    print(header)
    print(separator)

    for row in rows:
        print(format_row(row))

    print(separator)
    print(format_row(totals(rows), total=True))
    print("Input Total includes cache reads/writes; Uncached excludes them. N/A = not fully reported.")
    print("Thinking: Claude output_tokens_details.thinking_tokens; Codex N/A.")
    print("Turns: Codex completed_turns; Claude num_turns (provider-specific counts).")
    models = sorted({model for row in rows for model in row["actual_models"]})
    print(f"Actual models: {', '.join(models) if models else 'N/A'}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("phase")
    parser.add_argument("--run", help="집계할 run ID (기본: 가장 최근)")
    parser.add_argument("--json", action="store_true", help="표 대신 JSON 출력")
    args = parser.parse_args()

    phase_dir = PHASES_DIR / args.phase
    if not phase_dir.is_dir():
        sys.exit(f"오류: phases/{args.phase} 디렉토리가 없습니다.")
    run_dir = pick_run(phase_dir, args.run)
    rows = collect(run_dir)
    if not rows:
        sys.exit(f"오류: {run_dir.name}에 step 로그(json)가 없습니다.")

    if args.json:
        print(json.dumps({"run_id": run_dir.name, "phase": args.phase, "steps": rows, "total": totals(rows)}, ensure_ascii=False, indent=2))
    else:
        print_table(run_dir, rows)


if __name__ == "__main__":
    main()
