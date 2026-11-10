#!/usr/bin/env python3
"""Harness execute.py — phase 단위로 Claude Code 세션을 순차 실행한다."""

import argparse
import contextlib
import hashlib
import json
import subprocess
import sys
import threading
import time
import types
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import datetime, timezone
from functools import partial
from pathlib import Path
from typing import Literal, NoReturn, NotRequired, TypedDict, cast
from uuid import uuid4

# ---------------------------------------------------------------------------
# 타입 정의
# ---------------------------------------------------------------------------

StepStatus = Literal["pending", "completed", "error", "blocked"]


class StepEntry(TypedDict):
    step: int
    name: str
    status: StepStatus
    started_at: NotRequired[str]
    completed_at: NotRequired[str]
    failed_at: NotRequired[str]
    blocked_at: NotRequired[str]
    summary: NotRequired[str]
    ac: NotRequired[list[str]]
    model: NotRequired[str]
    reasoning_effort: NotRequired[str]
    error_message: NotRequired[str]
    blocked_reason: NotRequired[str]


class PhaseIndex(TypedDict):
    project: str
    phase: str
    steps: list[StepEntry]
    created_at: NotRequired[str]


# 최상위 index는 phase 목록과 선후 관계만 담는다. 상태는 worktree마다 사본이 갈라지고
# 여러 phase 브랜치를 merge할 때 충돌하므로 phase별 index.json의 step 상태로만 판단한다.
class PhaseEntry(TypedDict):
    dir: str
    depends_on: NotRequired[list[str]]


class PhasesIndex(TypedDict):
    phases: list[PhaseEntry]


# ---------------------------------------------------------------------------
# 상수
# ---------------------------------------------------------------------------

PROJECT_ROOT = Path(__file__).parent.parent
PHASES_DIR = PROJECT_ROOT / "phases"
MAX_RETRIES = 3
AC_TIMEOUT = 600
# summary는 이후 모든 step 프롬프트의 [CONTEXT]에 실리므로 길이를 제한한다.
SUMMARY_MAX_CHARS = 200
# [CONTEXT]에는 최근 step만 summary 전문을 싣고, 그 이전 step은 앞부분만 싣는다.
CONTEXT_FULL_STEPS = 1
CONTEXT_OLD_SUMMARY_CHARS = 120
# step 세션에 노출할 내장 도구. 도구 정의가 세션 기본 컨텍스트의 절반 이상을 차지한다
# (실측: 전체 15.7k → 이 목록 6.6k 토큰).
STEP_TOOLS = "Bash,Read,Write,Edit"
REASONING_EFFORTS = {
    "claude": ("low", "medium", "high", "xhigh", "max"),
    "codex": ("low", "medium", "high", "xhigh", "max", "ultra"),
}


# ---------------------------------------------------------------------------
# 유틸
# ---------------------------------------------------------------------------


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def select_reasoning_effort(step: StepEntry, provider: str, override: str | None = None) -> str | None:
    """CLI 옵션 > step > CLI 기본 설정. 모델별 지원 여부는 각 CLI가 검사한다."""
    effort = override if override is not None else step.get("reasoning_effort")
    if effort is not None and effort not in REASONING_EFFORTS[provider]:
        allowed = ", ".join(REASONING_EFFORTS[provider])
        raise ValueError(f"step {step['step']}: {provider} reasoning_effort는 {allowed} 중 하나여야 합니다: {effort!r}")
    return effort


def load_phase_index(phase_dir: Path) -> PhaseIndex:
    with open(phase_dir / "index.json", encoding="utf-8") as f:
        return cast(PhaseIndex, json.load(f))


def save_phase_index(phase_dir: Path, data: PhaseIndex) -> None:
    with open(phase_dir / "index.json", "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
        f.write("\n")


def load_phases_index() -> PhasesIndex:
    with open(PHASES_DIR / "index.json", encoding="utf-8") as f:
        return cast(PhasesIndex, json.load(f))


def save_phases_index(data: PhasesIndex) -> None:
    with open(PHASES_DIR / "index.json", "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
        f.write("\n")


def unmet_dependencies(phase_name: str) -> list[str]:
    """현재 브랜치에서 아직 완료되지 않은 선행 phase를 사유와 함께 반환한다."""
    phases = load_phases_index()["phases"]
    listed = {p["dir"] for p in phases}
    entry = next((p for p in phases if p["dir"] == phase_name), None)
    unmet: list[str] = []
    for dep in (entry or {}).get("depends_on", []):
        # prune_phase.py는 merge된 완료 phase를 목록에서 지운다. 목록에 없으면 완료로 본다.
        if dep not in listed:
            continue
        dep_dir = PHASES_DIR / dep
        if not (dep_dir / "index.json").is_file():
            unmet.append(f"{dep} (phase 파일 없음 — worktree base 브랜치 확인)")
            continue
        pending = [s for s in load_phase_index(dep_dir)["steps"] if s["status"] != "completed"]
        if pending:
            unmet.append(f"{dep} (미완료 step {len(pending)}개 — merge 후 그 브랜치에서 worktree 생성)")
    return unmet


def find_step(phase_index: PhaseIndex, step_num: int) -> StepEntry:
    for s in phase_index["steps"]:
        if s["step"] == step_num:
            return s
    raise KeyError(f"step {step_num} not found")


@contextlib.contextmanager
def progress_indicator(label: str = ""):
    """터미널 진행 표시기. with 문으로 사용하며 .elapsed 로 경과 시간을 읽는다."""
    frames = "◐◓◑◒"
    stop = threading.Event()
    t0 = time.monotonic()

    def _animate():
        idx = 0
        while not stop.wait(0.12):
            sec = int(time.monotonic() - t0)
            sys.stderr.write(f"\r{frames[idx % len(frames)]} {label} [{sec}s]")
            sys.stderr.flush()
            idx += 1
        sys.stderr.write("\r" + " " * (len(label) + 20) + "\r")
        sys.stderr.flush()

    th = threading.Thread(target=_animate, daemon=True)
    th.start()
    info = types.SimpleNamespace(elapsed=0.0)
    try:
        yield info
    finally:
        stop.set()
        th.join()
        info.elapsed = time.monotonic() - t0


# ---------------------------------------------------------------------------
# Git
# ---------------------------------------------------------------------------


def _git(*args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", *args],
        capture_output=True,
        text=True,
        cwd=PROJECT_ROOT,
    )


def is_worktree() -> bool:
    result = _git("rev-parse", "--show-toplevel")
    git_dir = Path(result.stdout.strip() + "/.git")
    # worktree에서 .git은 파일; 메인 저장소에서는 디렉토리
    if not git_dir.is_absolute():
        git_dir = PROJECT_ROOT / git_dir
    return git_dir.is_file()


def check_worktree() -> None:
    if not is_worktree():
        print("⚠ 현재 디렉토리는 git worktree가 아닙니다.")
        print("  worktree 외부에서 실행하면 main 브랜치에 직접 영향을 줄 수 있습니다.")
        try:
            answer = input("  계속하시겠습니까? [y/N] ").strip().lower()
        except EOFError:
            answer = ""
        if answer != "y":
            sys.exit(0)


def commit_step(phase_name: str, step: StepEntry) -> None:
    """AC를 통과한 step의 모든 변경을 하나의 커밋으로 남긴다."""
    message = f"harness({phase_name}): step{step['step']:02d} {step['name']}"
    for args in (("add", "-A"), ("commit", "-m", message)):
        result = _git(*args)
        if result.returncode != 0:
            raise RuntimeError(
                f"git {args[0]} 실패: {(result.stderr or result.stdout).strip()}"
            )


# ---------------------------------------------------------------------------
# 프롬프트 조합
# ---------------------------------------------------------------------------


def build_prompt(
    phase_dir: Path,
    step: StepEntry,
    completed: list[StepEntry],
    retry_error: str | None = None,
    interrupted: bool = False,
) -> str:
    step_file = phase_dir / f"step{step['step']:02d}-{step['name']}.md"
    task_content = step_file.read_text(encoding="utf-8")

    parts: list[str] = []

    if completed:
        lines = ["[CONTEXT - 이전 step 완료 목록]"]
        old_count = max(len(completed) - CONTEXT_FULL_STEPS, 0)
        for i, s in enumerate(completed):
            summary = s.get("summary", "")
            if i < old_count and len(summary) > CONTEXT_OLD_SUMMARY_CHARS:
                summary = summary[: CONTEXT_OLD_SUMMARY_CHARS - 1] + "…"
            lines.append(f"- {summary}")
        parts.append("\n".join(lines))

    if interrupted:
        parts.append(
            "[INTERRUPTED CONTEXT]\n"
            + "이전 실행이 비정상 종료되었다. 파일이 절반만 작성된 상태일 수 있다.\n"
            + "작업 전 반드시 아래를 수행하라:\n"
            + f'1. step{step["step"]:02d}-{step["name"]}.md의 "읽어야 할 파일"에 명시된 파일 상태를 먼저 확인하라.\n'
            + "2. 이미 올바르게 작성된 부분은 건드리지 말고, 누락된 부분만 완성하라.\n"
            + "3. 완료 후 정상 절차대로 index.json을 업데이트하라. 커밋은 하지 마라."
        )

    if retry_error:
        parts.append(
            "[RETRY CONTEXT]\n"
            + f"이전 시도에서 다음 오류가 발생했다: {retry_error}\n"
            + "이전 시도의 변경은 작업 트리에 남아 있다. 처음부터 다시 하지 말고,\n"
            + "`git status`와 `git diff`로 현재 상태를 확인한 뒤 위 오류와 관련된 부분만 수정하라."
        )

    parts.append(f"[TASK]\n{task_content}")

    parts.append(
        "[HARNESS - 상태 갱신 대상]\n"
        + f"현재 실행의 index 파일: {(phase_dir / 'index.json').resolve()}\n"
        + f"갱신할 step: {step['step']} ({step['name']})\n"
        + "작업 완료 후 이 파일의 해당 step 상태를 갱신하라. "
        + "작업 지시서에 다른 phase의 index 경로가 있으면 위 경로를 사용하라.\n"
        + f"summary는 {SUMMARY_MAX_CHARS}자 이내로 쓴다(넘으면 잘린다). "
        + "다음 step이 알아야 할 산출물 경로와 핵심 결론만 남긴다."
    )

    return "\n\n".join(parts)


# ---------------------------------------------------------------------------
# Claude 호출
# ---------------------------------------------------------------------------


def invoke_claude(prompt: str, model: str | None = None, reasoning_effort: str | None = None) -> str:
    """Claude 세션을 실행하고 출력을 반환한다. 실패 시 예외에 출력이 담긴다."""
    # --strict-mcp-config: 사용자 전역 MCP 서버를 로드하지 않아 세션 기본 컨텍스트를 줄인다.
    # --tools: 필요한 내장 도구만 노출해 매 턴 다시 읽는 기본 컨텍스트를 줄인다.
    # --output-format json: 로그에 토큰 사용량(usage)과 비용(total_cost_usd)을 남긴다.
    command = [
        "claude", "-p", prompt, "--dangerously-skip-permissions",
        "--strict-mcp-config", "--tools", STEP_TOOLS, "--output-format", "json",
    ]
    if model:
        command.extend(["--model", model])
    if reasoning_effort is not None:
        command.extend(["--effort", reasoning_effort])
    result = subprocess.run(
        command,
        cwd=PROJECT_ROOT,
        capture_output=True,
        text=True,
        timeout=1800,
    )
    result.check_returncode()
    return format_output(result.stdout, result.stderr)


# ---------------------------------------------------------------------------
# 로그
# ---------------------------------------------------------------------------


def _decode(output: str | bytes | None) -> str:
    if isinstance(output, bytes):
        return output.decode("utf-8", errors="replace")
    return output or ""


def format_output(stdout: str | bytes | None, stderr: str | bytes | None) -> str:
    return f"[STDOUT]\n{_decode(stdout)}\n[STDERR]\n{_decode(stderr)}"


def write_json(path: Path, data: dict) -> None:
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


@dataclass
class RunLog:
    phase_dir: Path
    provider: str
    model: str | None = None
    settings: dict = field(default_factory=dict)
    run_id: str = field(default_factory=lambda: datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S") + "-" + uuid4().hex)
    started_at: str = field(default_factory=now_iso)
    stop_reason: str | None = None
    model_observations: list[dict] = field(default_factory=list)
    step_model: Callable[[StepEntry], str | None] | None = None
    reasoning_effort: str | None = None

    @property
    def directory(self) -> Path:
        return self.phase_dir / "logs" / self.run_id

    def save(self, status: str, **fields) -> None:
        self.directory.mkdir(parents=True, exist_ok=True)
        actual_models = list(dict.fromkeys(
            model for observation in self.model_observations for model in observation["actual_models"]
        ))
        write_json(self.directory / "run.json", {
            "schema_version": 1, "run_id": self.run_id,
            "phase": self.phase_dir.name, "provider": self.provider,
            "requested_model": self.model, "settings": self.settings,
            "started_at": self.started_at, "status": status,
            "stop_reason": self.stop_reason, **fields,
            "actual_model": actual_models[0] if len(actual_models) == 1 else None,
            "actual_models": actual_models, "model_observations": self.model_observations,
        })


def output_metrics(output: str | None, provider: str) -> dict:
    """원래 토큰 필드의 의미를 유지한다. 사용량은 현재 컨텍스트 크기가 아니다."""
    if not isinstance(output, str) or provider not in ("codex", "claude"):
        return {}
    try:
        raw = (output or "").split("[STDOUT]\n", 1)[1].rsplit("\n[STDERR]\n", 1)[0]
        data = json.loads(raw)
    except (IndexError, ValueError, RecursionError):
        return {}
    if not isinstance(data, dict):
        return {}
    keys = ("input_tokens", "cached_input_tokens", "output_tokens") if provider == "codex" else (
        "input_tokens", "cache_creation_input_tokens", "cache_read_input_tokens", "output_tokens",
    )
    usage = data.get("usage")
    metrics: dict = {"usage_source": "codex.turn.completed.sum" if provider == "codex" else "claude.result.usage"}
    metrics["usage"] = {k: usage[k] for k in keys if isinstance(usage, dict) and type(usage.get(k)) is int and usage[k] >= 0}
    if provider == "claude":
        # thinking 토큰은 output_tokens의 세부 항목이다. 보고되지 않으면 기록하지 않는다.
        details = usage.get("output_tokens_details") if isinstance(usage, dict) else None
        thinking = details.get("thinking_tokens") if isinstance(details, dict) else None
        if type(thinking) is int and thinking >= 0:
            metrics["usage"]["thinking_tokens"] = thinking
        # modelUsage의 키가 실제 응답한 모델이다. 하위 에이전트 등으로 여러 개일 수 있다.
        model_usage = data.get("modelUsage")
        models = [k for k in model_usage if isinstance(k, str) and k] if isinstance(model_usage, dict) else []
        metrics["actual_model"] = models[0] if len(models) == 1 else None
        metrics["actual_models"] = models
        metrics["actual_model_source"] = "claude.result.modelUsage" if models else "not_reported"
        metrics["model_metadata_status"] = "result_reported" if models else "not_reported"
        if models:
            metrics["model_usage"] = {
                model: {
                    key: model_usage[model][source] for source, key in (("costUSD", "cost_usd"), ("thinkingTokens", "thinking_tokens"))
                    if isinstance(model_usage[model], dict) and type(model_usage[model].get(source)) in (int, float) and model_usage[model][source] >= 0
                }
                for model in models
            }
    for key in ("completed_turns", "failed_turns", "errors", "invalid_lines", "num_turns", "duration_ms", "duration_api_ms", "total_cost_usd"):
        value = data.get(key)
        if type(value) in (int, float) and value >= 0:
            metrics[key] = value
    if provider == "codex" and isinstance(data.get("tool_counts"), dict):
        metrics["tool_counts"] = {k: v for k, v in data["tool_counts"].items() if k in ("command_execution", "file_change", "mcp_tool_call", "web_search") and type(v) is int and v >= 0}
    if provider == "codex":
        metrics["actual_model"] = data.get("actual_model")
        metrics["actual_model_source"] = data.get("actual_model_source", "not_reported")
        for key in ("actual_models", "thread_id", "model_metadata_status", "command_outcomes"):
            if key in data:
                metrics[key] = data[key]
        total = metrics["usage"].get("input_tokens")
        cached = metrics["usage"].get("cached_input_tokens")
        if total is not None and cached is not None and cached <= total:
            metrics["uncached_input_tokens"] = total - cached
            metrics["cached_input_ratio"] = cached / total if total else None
    return metrics


@dataclass
class AttemptLog:
    run: RunLog
    step: StepEntry
    attempt: int
    prompt: str = ""
    output: str | None = None
    data: dict = field(default_factory=dict)

    @property
    def path(self) -> Path:
        return self.run.directory / f"step{self.step['step']:02d}-attempt{self.attempt + 1:02d}.log"

    def save(self) -> None:
        self.run.directory.mkdir(parents=True, exist_ok=True)
        self.path.write_text(f"[PROMPT]\n{self.prompt}\n\n{self.output or ''}\n", encoding="utf-8")
        write_json(self.path.with_suffix(".json"), {
            "schema_version": 1, "run_id": self.run.run_id,
            "step": self.step["step"], "attempt": self.attempt + 1,
            "provider": self.run.provider,
            "requested_model": self.run.model if self.run.provider != "claude" else self.step.get("model", self.run.model),
            "prompt_sha256": hashlib.sha256(self.prompt.encode("utf-8")).hexdigest(),
            **self.data, "metrics": output_metrics(self.output, self.run.provider),
        })


# ---------------------------------------------------------------------------
# Acceptance Criteria
# ---------------------------------------------------------------------------


def run_acceptance(step: StepEntry, results: list[dict] | None = None) -> str | None:
    """step의 AC 커맨드를 순서대로 실행한다. 실패 시 진단 메시지, 성공 시 None."""
    for command in step.get("ac", []):
        started = time.monotonic()
        try:
            result = subprocess.run(
                command,
                shell=True,
                cwd=PROJECT_ROOT,
                capture_output=True,
                text=True,
                timeout=AC_TIMEOUT,
            )
        except subprocess.TimeoutExpired:
            if results is not None:
                results.append({"command": command, "status": "timeout", "exit_code": None, "elapsed_seconds": time.monotonic() - started})
            return f"AC 타임아웃 ({AC_TIMEOUT}s): {command}"
        if results is not None:
            results.append({"command": command, "status": "passed" if result.returncode == 0 else "failed", "exit_code": result.returncode, "elapsed_seconds": time.monotonic() - started})
        if result.returncode != 0:
            output = (result.stdout + result.stderr).strip()
            return f"AC 실패 (exit {result.returncode}): {command}\n{output[-2000:]}"
    return None


# ---------------------------------------------------------------------------
# Step 실행
# ---------------------------------------------------------------------------


def run_step(
    phase_dir: Path,
    step: StepEntry,
    completed: list[StepEntry],
    attempt: int,
    retry_error: str | None,
    invoke: Callable[[str], str | None] | None = None,
    *,
    run: RunLog | None = None,
) -> StepStatus:
    run = run or RunLog(phase_dir, "custom" if invoke else "claude")
    log = AttemptLog(run, step, attempt)
    started = time.monotonic()
    log.data = {
        "started_at": now_iso(), "status": "running", "exit_code": None,
        "agent_status": None, "ac": {"status": "not_run", "results": []},
    }
    if run.step_model is not None:
        selected_model = run.step_model(step)
        log.data["requested_model"] = selected_model
        if invoke is not None:
            invoke = partial(invoke, model=selected_model)
    if run.provider in REASONING_EFFORTS:
        effort = select_reasoning_effort(step, run.provider, run.reasoning_effort)
        log.data["requested_reasoning_effort"] = effort
        log.data["reasoning_effort_source"] = (
            "cli" if run.reasoning_effort is not None else "step" if effort is not None else "cli_default"
        )
        if invoke is not None and effort is not None:
            invoke = partial(invoke, reasoning_effort=effort)
    log.save()
    try:
        status = _run_step(phase_dir, step, completed, attempt, retry_error, invoke, log)
        log.data["status"] = status
        return status
    except KeyboardInterrupt:
        log.data["status"] = "interrupted"
        if log.data["ac"]["status"] == "running":
            log.data["ac"]["status"] = "interrupted"
        raise
    except BaseException:
        log.data["status"] = "error"
        if log.data["ac"]["status"] == "running":
            log.data["ac"]["status"] = "error"
        raise
    finally:
        log.data.update(ended_at=now_iso(), elapsed_seconds=time.monotonic() - started)
        log.save()
        if run.provider in ("codex", "claude"):
            metrics = output_metrics(log.output, run.provider)
            run.model_observations.append({
                "step": step["step"], "attempt": attempt + 1,
                "actual_model": metrics.get("actual_model"),
                "actual_models": metrics.get("actual_models", []),
                "source": metrics.get("actual_model_source", "not_reported"),
                "status": metrics.get("model_metadata_status", "not_reported"),
                "thread_id": metrics.get("thread_id"),
            })


def _run_step(
    phase_dir: Path,
    step: StepEntry,
    completed: list[StepEntry],
    attempt: int,
    retry_error: str | None,
    invoke: Callable[[str], str | None] | None,
    log: AttemptLog,
) -> StepStatus:
    phase_index = load_phase_index(phase_dir)
    current = find_step(phase_index, step["step"])
    interrupted = "started_at" in current and current["status"] == "pending"
    log.data["execution_context"] = {
        "interrupted_resume": interrupted, "retry": retry_error is not None,
        "initial_step_status": current["status"],
    }

    # 시도마다 pending으로 리셋해 이전 시도의 결과가 재사용되지 않게 한다.
    # started_at 기록 (Claude 호출 직전 — 중단 감지용)
    current["status"] = "pending"
    current["started_at"] = now_iso()
    save_phase_index(phase_dir, phase_index)

    prompt = build_prompt(
        phase_dir, step, completed, retry_error, interrupted
    )
    log.prompt = prompt
    log.data["prompt_chars"] = len(prompt)
    log.data["prompt_bytes"] = len(prompt.encode("utf-8"))
    log.save()

    print(
        f"  → Step {step['step']} ({step['name']}) 실행 중..."
        + f" (시도 {attempt + 1}/{MAX_RETRIES})"
    )
    try:
        with progress_indicator() as pi:
            output = (invoke or partial(
                invoke_claude, model=step.get("model", log.run.model),
                reasoning_effort=log.data.get("requested_reasoning_effort"),
            ))(prompt)
        log.output = output
        log.data["exit_code"] = 0
        log.data["agent_elapsed_seconds"] = pi.elapsed
    except (OSError, subprocess.SubprocessError) as exc:
        log.data["agent_elapsed_seconds"] = pi.elapsed
        log.data["process_error"] = type(exc).__name__
        if isinstance(exc, subprocess.CalledProcessError):
            log.data["exit_code"] = exc.returncode
        message = str(exc)
        if isinstance(exc, (subprocess.CalledProcessError, subprocess.TimeoutExpired)):
            diagnostic = _decode(exc.stderr)
            if diagnostic:
                message += "\n" + diagnostic[-2000:].strip()
            log.output = format_output(exc.stdout, exc.stderr)
        else:
            log.output = message
        log.save()
        log_path = log.path
        print(f"    로그: {log_path.relative_to(PROJECT_ROOT)}", file=sys.stderr)
        phase_index = load_phase_index(phase_dir)
        updated = find_step(phase_index, step["step"])
        updated["status"] = "error"
        updated["failed_at"] = now_iso()
        updated["error_message"] = message
        save_phase_index(phase_dir, phase_index)
        print(f"  ✗ Step {step['step']} 실행 실패: {message}", file=sys.stderr)
        return "error"
    elapsed = int(pi.elapsed)

    log.save()
    log_path = log.path
    print(
        f"  ✓ Step {step['step']} 세션 종료 [{elapsed}s]"
        + f" — 로그: {log_path.relative_to(PROJECT_ROOT)}"
    )
    # Claude 종료 후 index.json 재읽기
    phase_index = load_phase_index(phase_dir)
    updated = find_step(phase_index, step["step"])
    status = updated["status"]
    log.data["agent_status"] = status

    # status가 여전히 pending이면 Claude가 index.json을 업데이트하지 않은 것
    if status == "pending":
        failure = "index.json이 업데이트되지 않음"
    elif status != "completed":
        return status
    else:
        # 에이전트의 completed 보고는 AC를 직접 실행해 확인한다.
        ac = log.data["ac"]
        ac["status"] = "running" if updated.get("ac") else "not_configured"
        log.save()
        failure = run_acceptance(updated, ac["results"])
        if updated.get("ac"):
            ac["status"] = "failed" if failure else "passed"

    if failure:
        updated["status"] = "error"
        updated["failed_at"] = now_iso()
        updated["error_message"] = failure
        save_phase_index(phase_dir, phase_index)
        print(f"  ✗ Step {step['step']} {failure.splitlines()[0]}", file=sys.stderr)
        return "error"

    # AC 통과 시각은 execute.py가 기록한다 (에이전트가 남긴 값은 덮어쓴다).
    # 커밋 전에 저장해 step 커밋에 함께 포함되게 한다.
    updated["completed_at"] = now_iso()
    summary = updated.get("summary", "")
    if len(summary) > SUMMARY_MAX_CHARS:
        updated["summary"] = summary[: SUMMARY_MAX_CHARS - 1] + "…"
        log.data["summary_truncated_from"] = len(summary)
    save_phase_index(phase_dir, phase_index)
    return "completed"


# ---------------------------------------------------------------------------
# Phase 실행
# ---------------------------------------------------------------------------


def _abort_blocked(phase_name: str, step: StepEntry) -> NoReturn:
    print(f"  ✗ Step {step['step']} ({step['name']}) — blocked, 수동 해결 필요")
    print(f"    사유: {step.get('blocked_reason', '')}")
    sys.exit(1)


def _run_step_with_retry(
    phase_dir: Path,
    step: StepEntry,
    completed: list[StepEntry],
    phase_name: str,
    invoke: Callable[[str], str | None] | None = None,
    *,
    run: RunLog | None = None,
) -> StepEntry:
    """단일 step을 최대 MAX_RETRIES회 실행한다. 성공 시 갱신된 StepEntry 반환."""
    retry_error: str | None = (
        step.get("error_message") if step["status"] == "error" else None
    )

    for attempt in range(MAX_RETRIES):
        final_status = run_step(
            phase_dir, step, completed, attempt, retry_error, invoke, run=run
        )

        phase_index = load_phase_index(phase_dir)
        updated = find_step(phase_index, step["step"])

        if final_status == "completed":
            try:
                commit_step(phase_name, updated)
            except RuntimeError as exc:
                if run:
                    run.stop_reason = "commit_failed"
                print(f"  ✗ Step {step['step']} 커밋 실패: {exc}", file=sys.stderr)
                sys.exit(1)
            print(f"  ✓ Step {step['step']} ({step['name']}) — 완료, 커밋됨")
            return updated

        if final_status == "blocked":
            if run:
                run.stop_reason = "blocked"
            _abort_blocked(phase_name, updated)

        retry_error = updated.get("error_message", "index.json이 업데이트되지 않음")
        if attempt < MAX_RETRIES - 1:
            print(f"  ! Step {step['step']} 실패, 재시도 ({attempt + 2}/{MAX_RETRIES})")

    print(f"  ✗ Step {step['step']} ({step['name']}) — {MAX_RETRIES}회 실패, 중단")
    sys.exit(1)


def run_phase(
    phase_name: str, *, invoke: Callable[[str], str | None] | None = None,
    provider: str | None = None, model: str | None = None, settings: dict | None = None,
    step_model: Callable[[StepEntry], str | None] | None = None,
    reasoning_effort: str | None = None,
) -> None:
    phase_dir = PHASES_DIR / phase_name
    if not phase_dir.exists():
        print(f"오류: phases/{phase_name} 디렉토리가 없습니다.")
        sys.exit(1)
    engine = provider or ("custom" if invoke else "claude")
    if engine in REASONING_EFFORTS:
        # 잘못된 설정으로 앞 step을 실행·커밋한 뒤 실패하지 않도록 사전 검증한다.
        for step in load_phase_index(phase_dir)["steps"]:
            if step["status"] != "completed":
                select_reasoning_effort(step, engine, reasoning_effort)
    unmet = unmet_dependencies(phase_name)
    if unmet:
        print(f"오류: 선행 phase가 이 브랜치에 완료 상태로 들어와 있지 않습니다: {', '.join(unmet)}")
        sys.exit(1)

    defaults = {"timeout_seconds": 1800, "tools": STEP_TOOLS, "strict_mcp_config": True} if engine == "claude" else {}
    run = RunLog(
        phase_dir, engine, model, {**defaults, **(settings or {}), "reasoning_effort": reasoning_effort},
        step_model=step_model, reasoning_effort=reasoning_effort,
    )
    run.save("running")
    print(f"실행 ID: {run.run_id}")
    started = time.monotonic()
    status = "error"
    try:
        _run_phase(phase_name, phase_dir, invoke, run)
        status = "completed"
    except KeyboardInterrupt:
        status = "interrupted"
        raise
    finally:
        if run.stop_reason == "blocked":
            status = "blocked"
        run.save(status, ended_at=now_iso(), elapsed_seconds=time.monotonic() - started)


def _run_phase(phase_name: str, phase_dir: Path, invoke: Callable[[str], str | None] | None, run: RunLog) -> None:
    phase_index = load_phase_index(phase_dir)

    if "created_at" not in phase_index:
        phase_index["created_at"] = now_iso()
        save_phase_index(phase_dir, phase_index)

    completed: list[StepEntry] = []

    for step in phase_index["steps"]:
        if step["status"] == "completed":
            print(f"  ✓ Step {step['step']} ({step['name']}) — 완료됨, 건너뜀")
            completed.append(step)
            continue

        if step["status"] == "blocked":
            run.stop_reason = "blocked"
            _abort_blocked(phase_name, step)

        completed.append(
            _run_step_with_retry(phase_dir, step, completed, phase_name, invoke, run=run)
        )

    print(f"\n✓ Phase '{phase_name}' 완료!")


# ---------------------------------------------------------------------------
# 진입점
# ---------------------------------------------------------------------------


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("phase", help="실행할 phase 디렉토리명")
    parser.add_argument("--reasoning-effort", choices=REASONING_EFFORTS["claude"],
                        help="모든 step의 추론 강도 재정의 (생략 시 step 설정, 없으면 CLI 기본 설정)")
    args = parser.parse_args(argv)
    check_worktree()
    try:
        run_phase(args.phase, reasoning_effort=args.reasoning_effort)
    except ValueError as exc:
        parser.error(str(exc))


if __name__ == "__main__":
    main()
