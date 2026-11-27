"""파이프라인 실행과 task에 연결할 지표를 로컬 JSONL에 추가한다."""

import json
import sys
from datetime import datetime
from pathlib import Path
from typing import Any

from lib import state


def record(root: Path, current: dict[str, Any], event: str, **details: Any) -> None:
    task = current['task']
    timestamp = state.now()
    elapsed = datetime.fromisoformat(timestamp) - datetime.fromisoformat(task['started_at'])
    row = {
        'event': event,
        'timestamp': timestamp,
        'run_id': current.get('run_id', current['started_at']),
        'plan': current['plan'],
        'task_id': task['id'],
        'task_started_at': task['started_at'],
        'task_elapsed_seconds': elapsed.total_seconds(),
        'tool': current['tool'],
        'session_id': current['session_id'],
        'gate_blocks': task['gate_blocks'],
        **details,
    }
    path = state.state_path(root).with_name('events.jsonl')
    try:
        with path.open('a', encoding='utf-8') as output:
            output.write(json.dumps(row, ensure_ascii=False) + '\n')
    except OSError as exc:
        # 지표 쓰기가 실패해도 게이트의 차단 결과는 전달한다.
        print(f'metrics: {exc}', file=sys.stderr)
