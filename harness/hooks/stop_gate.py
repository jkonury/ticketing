#!/usr/bin/env python3
"""Stop 훅 검증 게이트. 파이프라인 세션에서만 동작한다.

사용법: stop_gate.py --tool claude|codex --config <gate-rules.toml>
출력: 막을 때만 `{"decision": "block", "reason": ...}`. 통과면 아무것도 출력하지 않는다.
"""

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from lib import gate, hook_input, state  # noqa: E402


def evaluate(hook: hook_input.HookInput, config_path: Path) -> str | None:
    """막아야 하면 reason을, 아니면 None을 돌려준다. 상태 파일을 갱신한다."""
    root = state.repo_root(hook.cwd)
    current = state.load(root)
    if current is None or not hook.session_id or current.get('session_id') != hook.session_id:
        return None
    return gate.evaluate(root, current, config_path)


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
