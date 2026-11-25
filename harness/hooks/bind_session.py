#!/usr/bin/env python3
"""PostToolUse(Bash) 훅. `pipeline.py start|resume`를 실행한 세션을 파이프라인 상태에 묶는다.

스킬 본문에서는 두 도구 모두 세션 ID를 안정적으로 얻을 수 없어서, 훅 입력의 session_id를 쓴다.
사용법: bind_session.py --tool claude|codex
"""

import argparse
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from lib import hook_input, state  # noqa: E402

BIND_COMMAND = re.compile(r'harness/bin/pipeline\.py["\']?\s+(start|resume)\b')


def bind(hook: hook_input.HookInput) -> bool:
    if not hook.command or not hook.session_id or not BIND_COMMAND.search(hook.command):
        return False
    root = state.repo_root(hook.cwd)
    current = state.load(root)
    if current is None or current.get('session_id'):
        return False
    current['session_id'] = hook.session_id
    current['tool'] = hook.tool
    current['bound_at'] = state.now()
    state.save(root, current)
    return True


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('--tool', required=True, choices=hook_input.TOOLS)
    args = parser.parse_args()
    try:
        bind(hook_input.parse(args.tool, sys.stdin.read()))
    except Exception as exc:  # 바인딩 실패가 도구 호출 자체를 깨뜨리지 않게 한다.
        print(f'bind_session: {exc}', file=sys.stderr)
    return 0


if __name__ == '__main__':
    sys.exit(main())
