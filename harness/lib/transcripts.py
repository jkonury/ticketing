"""Claude Code·Codex 대화 기록에서 task 구간의 토큰을 같은 항목으로 집계한다.

대화 내용은 읽지만 결과에는 숫자만 돌려준다. 기록이 없거나 구간에 토큰 이벤트가 없으면
0이 아니라 None(미상)을 돌려준다.
"""

import json
from datetime import datetime
from pathlib import Path

FIELDS = ('input', 'cache_read', 'cache_write', 'output')


def parse_time(value: str) -> datetime:
    return datetime.fromisoformat(value.replace('Z', '+00:00'))


def find_session_file(tool: str, session_id: str, root: Path) -> Path | None:
    if tool == 'claude':
        pattern = f'*/{session_id}.jsonl'
    elif tool == 'codex':
        pattern = f'*/*/*/rollout-*-{session_id}.jsonl'
    else:
        return None
    return next(iter(sorted(root.glob(pattern))), None)


def _rows(path: Path):
    with path.open(encoding='utf-8') as lines:
        for line in lines:
            try:
                yield json.loads(line)
            except json.JSONDecodeError:
                continue


def _claude_tokens(path: Path, start: datetime, end: datetime) -> dict[str, int] | None:
    # 같은 message.id가 스트리밍 중 여러 줄로 남으므로 첫 시각과 가장 큰 사용량을 쓴다.
    messages: dict[str, tuple[datetime, dict[str, int]]] = {}
    for row in _rows(path):
        message = row.get('message')
        if row.get('type') != 'assistant' or not isinstance(message, dict):
            continue
        usage = message.get('usage')
        if not isinstance(usage, dict) or 'timestamp' not in row:
            continue
        values = {
            'input': usage.get('input_tokens', 0),
            'cache_read': usage.get('cache_read_input_tokens', 0),
            'cache_write': usage.get('cache_creation_input_tokens', 0),
            'output': usage.get('output_tokens', 0),
        }
        key = message.get('id') or row.get('uuid')
        at = parse_time(row['timestamp'])
        if key in messages:
            first, previous = messages[key]
            values = {name: max(values[name], previous[name]) for name in FIELDS}
            at = min(first, at)
        messages[key] = (at, values)
    selected = [values for at, values in messages.values() if start <= at <= end]
    if not selected:
        return None
    return {name: sum(values[name] for values in selected) for name in FIELDS}


def _codex_cumulative(usage: dict) -> dict[str, int]:
    cached = usage.get('cached_input_tokens', 0)
    return {
        # Codex의 입력은 캐시 읽기를 포함하므로 뺀다.
        'input': usage.get('input_tokens', 0) - cached,
        'cache_read': cached,
        'cache_write': usage.get('cache_write_input_tokens', 0),
        'output': usage.get('output_tokens', 0),
    }


def _codex_tokens(path: Path, start: datetime, end: datetime) -> dict[str, int] | None:
    before = {name: 0 for name in FIELDS}
    last = None
    in_range = False
    for row in _rows(path):
        payload = row.get('payload')
        if row.get('type') != 'event_msg' or not isinstance(payload, dict):
            continue
        if payload.get('type') != 'token_count' or not payload.get('info'):
            continue
        at = parse_time(row['timestamp'])
        total = _codex_cumulative(payload['info']['total_token_usage'])
        if at < start:
            before = total
        elif at <= end:
            last = total
            in_range = True
    if not in_range:
        return None
    return {name: last[name] - before[name] for name in FIELDS}


def task_tokens(tool: str, session_id: str, start: str, end: str, root: Path) -> dict[str, int] | None:
    """[start, end] 구간의 정규화한 토큰. 기록이 없거나 구간에 이벤트가 없으면 None."""
    if not session_id:
        return None
    path = find_session_file(tool, session_id, root)
    if path is None:
        return None
    reader = _claude_tokens if tool == 'claude' else _codex_tokens
    return reader(path, parse_time(start), parse_time(end))
