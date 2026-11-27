"""변경 경로에 맞는 검증 명령을 고르고 실행한다. 경로 규칙은 프로젝트 설정 파일(TOML)이 정한다.

설정 형식:
    [modules]                 # 경로 접두사 → 치환값. per_module 규칙의 {module}
    "core" = ":core"

    [[rules]]                 # 파일마다 위에서부터 처음 맞는 규칙 하나만 적용
    name = "kotlin"
    globs = ["*"]             # fnmatch, 저장소 루트 기준 경로
    exclude = ["*.md"]
    per_module = true         # 파일이 [modules]에 속할 때만 맞음
    skip = false              # true면 검사 없이 통과
    commands = ["... {module}:ktlintCheck", "... {files}"]
"""

import hashlib
import shlex
import subprocess
import tomllib
from dataclasses import dataclass
from fnmatch import fnmatchcase
from pathlib import Path
from time import monotonic
from typing import Any

from lib import metrics, state

OUTPUT_TAIL_LINES = 40
COMMAND_TIMEOUT_SECONDS = 1500


@dataclass(frozen=True)
class CommandResult:
    command: str
    returncode: int
    output: str


def load_config(path: Path) -> dict[str, Any]:
    with path.open('rb') as f:
        return tomllib.load(f)


def _git(root: Path, *args: str) -> bytes:
    return subprocess.run(['git', '-C', str(root), *args], capture_output=True, check=True, timeout=30).stdout


def changed_files(root: Path) -> list[str]:
    """커밋되지 않은 변경(스테이지·작업 트리·추적 안 된 파일). 이름 변경은 양쪽 경로를 모두 넣는다."""
    entries = _git(root, 'status', '--porcelain=v1', '-z', '--untracked-files=all').decode().split('\0')
    files: list[str] = []
    i = 0
    while i < len(entries):
        entry = entries[i]
        i += 1
        if len(entry) < 4:
            continue
        files.append(entry[3:])
        if entry[0] in 'RC':
            files.append(entries[i])
            i += 1
    return sorted(set(files))


def fingerprint(root: Path) -> str:
    """작업 트리 상태의 지문. 같은 상태로 다시 멈출 때 검사를 반복하지 않으려고 쓴다."""
    digest = hashlib.sha256()
    try:
        digest.update(_git(root, 'diff', 'HEAD', '--binary'))
    except subprocess.CalledProcessError:
        digest.update(_git(root, 'diff', '--cached', '--binary'))
    untracked = _git(root, 'ls-files', '--others', '--exclude-standard', '-z').decode().split('\0')
    for rel in sorted(p for p in untracked if p):
        digest.update(rel.encode())
        path = root / rel
        if path.is_file():
            digest.update(hashlib.sha256(path.read_bytes()).digest())
    return digest.hexdigest()


def _module_of(path: str, modules: dict[str, str]) -> str | None:
    best = None
    for prefix in modules:
        if path == prefix or path.startswith(prefix.rstrip('/') + '/'):
            if best is None or len(prefix) > len(best):
                best = prefix
    return best


def _matches(path: str, rule: dict[str, Any]) -> bool:
    if not any(fnmatchcase(path, g) for g in rule.get('globs', ['*'])):
        return False
    return not any(fnmatchcase(path, g) for g in rule.get('exclude', []))


def select_commands(files: list[str], config: dict[str, Any]) -> list[str]:
    modules: dict[str, str] = config.get('modules', {})
    rules: list[dict[str, Any]] = config.get('rules', [])
    # 규칙 순서·모듈 등장 순서를 유지하며 명령을 모은다.
    buckets: dict[tuple[int, str | None], list[str]] = {}
    for path in files:
        for index, rule in enumerate(rules):
            module = None
            if rule.get('per_module'):
                module = _module_of(path, modules)
                if module is None:
                    continue
            if not _matches(path, rule):
                continue
            if not rule.get('skip'):
                buckets.setdefault((index, module), []).append(path)
            break
    commands: list[str] = []
    for (index, module), paths in sorted(buckets.items(), key=lambda kv: (kv[0][0], kv[0][1] or '')):
        values = {
            'module': modules.get(module, '') if module else '',
            'files': ' '.join(shlex.quote(p) for p in paths),
        }
        for template in rules[index].get('commands', []):
            command = template.format(**values)
            if command not in commands:
                commands.append(command)
    return commands


def run(root: Path, commands: list[str]) -> list[CommandResult]:
    """모든 명령을 순서대로 실행하고 실패한 결과만 돌려준다."""
    failures: list[CommandResult] = []
    for command in commands:
        try:
            proc = subprocess.run(
                ['bash', '-o', 'pipefail', '-c', command], cwd=root, capture_output=True, text=True,
                timeout=COMMAND_TIMEOUT_SECONDS,
            )
        except subprocess.TimeoutExpired:
            failures.append(CommandResult(command, 124, f'{COMMAND_TIMEOUT_SECONDS}초 안에 끝나지 않았다.'))
            continue
        if proc.returncode != 0:
            lines = (proc.stdout + proc.stderr).rstrip().splitlines()
            failures.append(CommandResult(command, proc.returncode, '\n'.join(lines[-OUTPUT_TAIL_LINES:])))
    return failures


def evaluate(root: Path, current: dict[str, Any], config_path: Path) -> str | None:
    """검증 실패 사유를 돌려준다. 검사 결과와 차단 횟수는 두 진입점이 공유한다."""
    files = changed_files(root)
    if not files:
        return None
    task = current.get('task') or state.new_task('_', [])
    tree_print = fingerprint(root)
    if task.get('last_pass_fingerprint') == tree_print:
        return None
    current['task'] = task
    if task['gate_blocks'] >= state.MAX_GATE_BLOCKS:
        if not task['gate_unresolved']:
            task['gate_unresolved'] = True
            state.save(root, current)
            metrics.record(root, current, 'gate', outcome='unresolved')
        return None

    commands = task.get('ac') or select_commands(files, load_config(config_path))
    if not commands:
        return None
    started = monotonic()
    failures = run(root, commands)
    duration = round(monotonic() - started, 3)
    source = 'ac' if task.get('ac') else 'path_rules'
    task['last_gate_at'] = state.now()
    if failures:
        task['gate_blocks'] += 1
    else:
        task['last_pass_fingerprint'] = tree_print
        task['gate_unresolved'] = False
    state.save(root, current)
    metrics.record(root, current, 'gate', outcome='blocked' if failures else 'passed',
                   source=source, duration_seconds=duration)
    if not failures:
        return None

    source_label = 'task AC' if source == 'ac' else '변경 경로 규칙'
    parts = [
        f'[검증 게이트] {source_label} 검사 실패 ({task["gate_blocks"]}/{state.MAX_GATE_BLOCKS}회 차단). '
        '실패를 고친 뒤 작업을 마친다. Stop 훅은 상한을 넘기면 종료를 허용하고 미해결로 기록한다.',
    ]
    for failure in failures:
        parts.append(f'$ {failure.command}  (exit {failure.returncode})\n{failure.output}')
    return '\n\n'.join(parts)
