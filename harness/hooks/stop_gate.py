#!/usr/bin/env python3
"""Stop 훅 검증 게이트. 파이프라인 세션에서만 동작한다.

사용법: stop_gate.py --tool claude|codex --config <gate-rules.toml>
출력: 막을 때만 `{"decision": "block", "reason": ...}`. 통과면 아무것도 출력하지 않는다.
"""

import argparse
import sys
from pathlib import Path
from time import monotonic

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from lib import gate, hook_input, metrics, state  # noqa: E402


def evaluate(hook: hook_input.HookInput, config_path: Path) -> str | None:
    """막아야 하면 reason을, 아니면 None을 돌려준다. 상태 파일을 갱신한다."""
    root = state.repo_root(hook.cwd)
    current = state.load(root)
    if current is None or not hook.session_id or current.get('session_id') != hook.session_id:
        return None
    task = current.get('task') or state.new_task('_', [])
    current['task'] = task

    files = gate.changed_files(root)
    if not files:
        return None
    tree_print = gate.fingerprint(root)
    if task.get('last_pass_fingerprint') == tree_print:
        return None
    if task['gate_blocks'] >= state.MAX_GATE_BLOCKS:
        if not task['gate_unresolved']:
            task['gate_unresolved'] = True
            state.save(root, current)
            metrics.record(root, current, 'gate', outcome='unresolved')
        return None

    commands = list(task.get('ac') or []) or gate.select_commands(files, gate.load_config(config_path))
    started = monotonic()
    failures = gate.run(root, commands)
    duration = round(monotonic() - started, 3)
    source = 'ac' if task.get('ac') else 'path_rules'
    task['last_gate_at'] = state.now()
    if failures:
        task['gate_blocks'] += 1
    else:
        task['last_pass_fingerprint'] = tree_print
        task['gate_unresolved'] = False
    state.save(root, current)
    if commands:
        metrics.record(root, current, 'gate', outcome='blocked' if failures else 'passed',
                       source=source, duration_seconds=duration)
    if not failures:
        return None

    source_label = 'task AC' if source == 'ac' else '변경 경로 규칙'
    parts = [
        f'[검증 게이트] {source_label} 검사 실패 ({task["gate_blocks"]}/{state.MAX_GATE_BLOCKS}회 차단). '
        '실패를 고친 뒤 작업을 마친다. 상한을 넘기면 통과시키고 미해결로 기록한다.',
    ]
    for failure in failures:
        parts.append(f'$ {failure.command}  (exit {failure.returncode})\n{failure.output}')
    return '\n\n'.join(parts)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('--tool', required=True, choices=hook_input.TOOLS)
    parser.add_argument('--config', required=True, type=Path)
    args = parser.parse_args()
    hook = hook_input.parse(args.tool, sys.stdin.read())
    config_path = args.config if args.config.is_absolute() else state.repo_root(hook.cwd) / args.config
    reason = evaluate(hook, config_path)
    if reason:
        print(hook_input.stop_block(reason))
    return 0


if __name__ == '__main__':
    sys.exit(main())
