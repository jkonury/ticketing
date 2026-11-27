#!/usr/bin/env python3
"""파이프라인 상태 파일을 다룬다. 스킬(`/implement` 등)이 Bash로 호출한다.

  pipeline.py start --plan <계획 경로> [--force]   상태 생성. 세션 바인딩은 PostToolUse 훅이 한다
  pipeline.py task <id> [--ac '<명령>']...         현재 task 전환. AC가 있으면 게이트가 AC를 실행한다
  pipeline.py resume                               다른 세션에서 이어 갈 때 세션 바인딩을 푼다(다음 훅이 다시 묶음)
  pipeline.py status                               상태 출력
  pipeline.py end                                  상태 삭제(마지막 상태를 출력)
"""

import argparse
import json
import sys
from pathlib import Path
from uuid import uuid4

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from lib import metrics, state  # noqa: E402


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog='pipeline.py')
    sub = parser.add_subparsers(dest='cmd', required=True)
    start = sub.add_parser('start')
    start.add_argument('--plan', required=True)
    start.add_argument('--force', action='store_true')
    task = sub.add_parser('task')
    task.add_argument('id')
    task.add_argument('--ac', action='append', default=[])
    for name in ('resume', 'status', 'end'):
        sub.add_parser(name)
    args = parser.parse_args(argv)

    root = state.repo_root(Path.cwd())
    current = state.load(root)

    if args.cmd == 'start':
        if current is not None and not args.force:
            print(f'이미 진행 중인 파이프라인이 있다: {current.get("plan")}. '
                  '이어 가려면 resume, 새로 시작하려면 --force.', file=sys.stderr)
            return 1
        previous = current
        current = {
            'version': 1, 'run_id': str(uuid4()), 'plan': args.plan, 'session_id': None, 'tool': None,
            'started_at': state.now(), 'task': None,
        }
        state.save(root, current)
        if previous and previous.get('task'):
            metrics.record(root, previous, 'task_ended', reason='replaced')
    elif current is None:
        print('진행 중인 파이프라인이 없다.', file=sys.stderr)
        return 1
    elif args.cmd == 'task':
        previous = current
        current = {**current, 'task': state.new_task(args.id, args.ac)}
        state.save(root, current)
        if previous.get('task'):
            metrics.record(root, previous, 'task_ended', reason='task_changed')
    elif args.cmd == 'resume':
        current['session_id'] = None
        state.save(root, current)
    elif args.cmd == 'end':
        state.clear(root)
        if current.get('task'):
            metrics.record(root, current, 'task_ended', reason='pipeline_ended')

    print(json.dumps(current, ensure_ascii=False, indent=2))
    return 0


if __name__ == '__main__':
    sys.exit(main())
