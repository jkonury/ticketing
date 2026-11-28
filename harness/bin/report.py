#!/usr/bin/env python3
"""`.metrics/events.jsonl`과 도구별 대화 기록으로 계획별 지표 리포트를 만든다.

  report.py [계획 slug 또는 경로]... [--events PATH] [--out DIR] [--claude-root DIR] [--codex-root DIR]

계획을 생략하면 이벤트에 있는 모든 계획을 만든다. 같은 입력이면 같은 출력을 낸다.
리포트에는 세션 ID·경로·대화 내용을 넣지 않고 숫자와 도구 이름만 쓴다.
"""

import argparse
import json
import re
import sys
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from lib import transcripts  # noqa: E402

REPO = Path(__file__).resolve().parents[2]
SUMMARY = re.compile(r'<!-- summary: (\{.*\}) -->')
START, END = '<!-- report:start -->', '<!-- report:end -->'
UNKNOWN = '미상'


def slug_of(plan: str) -> str:
    name = Path(plan).stem
    return re.sub(r'^\d{4}-\d{2}-\d{2}-', '', name)


def load_events(path: Path) -> list[dict]:
    rows = []
    for line in path.read_text(encoding='utf-8').splitlines():
        try:
            rows.append(json.loads(line))
        except json.JSONDecodeError:
            continue
    return rows


def build_rows(events: list[dict], plan: str, roots: dict[str, Path]) -> list[dict]:
    """task 구간(run_id, task_id, task_started_at)마다 한 행. 종료 이벤트가 있는 구간만 쓴다."""
    ended = {}
    gates: dict[tuple, list[dict]] = {}
    for event in events:
        if event['plan'] != plan:
            continue
        key = (event['run_id'], event['task_id'], event['task_started_at'])
        if event['event'] == 'task_ended':
            ended[key] = event
        elif event['event'] == 'gate':
            gates.setdefault(key, []).append(event)
    rows = []
    for key, event in ended.items():
        attempts = sorted(gates.get(key, []), key=lambda gate: gate['timestamp'])
        seconds = (datetime.fromisoformat(event['timestamp'])
                   - datetime.fromisoformat(event['task_started_at'])).total_seconds()
        tool = event['tool']
        tokens = None
        if tool in roots:
            tokens = transcripts.task_tokens(
                tool, event['session_id'], event['task_started_at'], event['timestamp'], roots[tool])
        rows.append({
            'task_id': event['task_id'],
            'started_at': event['task_started_at'],
            'tool': tool,
            'seconds': seconds,
            'blocks': event['gate_blocks'],
            'first_pass': attempts[0]['outcome'] == 'passed' if attempts else None,
            'unresolved': any(gate['outcome'] == 'unresolved' for gate in attempts),
            'tokens': tokens,
        })
    rows.sort(key=lambda row: (row['started_at'], row['task_id']))
    return rows


def duration(seconds: float) -> str:
    total = round(seconds)
    return f'{total // 60}분 {total % 60:02d}초'


def number(value: int) -> str:
    return f'{value:,}'


def token_cells(tokens: dict | None) -> list[str]:
    if tokens is None:
        return [UNKNOWN] * 4
    return [number(tokens[name]) for name in transcripts.FIELDS]


def first_pass_text(value: bool | None) -> str:
    return '-' if value is None else ('예' if value else '아니오')


def summarize(rows: list[dict]) -> dict:
    known = [row['tokens'] for row in rows if row['tokens'] is not None]
    judged = [row for row in rows if row['first_pass'] is not None]
    return {
        'tools': sorted({row['tool'] for row in rows}),
        'started_at': rows[0]['started_at'],
        'tasks': len(rows),
        'seconds': sum(row['seconds'] for row in rows),
        'blocks': sum(row['blocks'] for row in rows),
        'first_pass': sum(1 for row in judged if row['first_pass']),
        'judged': len(judged),
        'unresolved': sum(1 for row in rows if row['unresolved']),
        'unknown_tokens': len(rows) - len(known),
        'tokens': {name: sum(tokens[name] for tokens in known) for name in transcripts.FIELDS},
    }


def render(slug: str, rows: list[dict]) -> str:
    summary = summarize(rows)
    lines = [
        f'# {slug} 지표', '',
        f'<!-- summary: {json.dumps({"slug": slug, **summary}, ensure_ascii=False, sort_keys=True)} -->', '',
        '| task | 도구 | 벽시계 시간 | 게이트 차단 | AC 첫 시도 통과 | 미해결 | 입력 | 캐시 읽기 | 캐시 쓰기 | 출력 |',
        '| --- | --- | ---: | ---: | --- | --- | ---: | ---: | ---: | ---: |',
    ]
    for row in rows:
        cells = [row['task_id'], row['tool'], duration(row['seconds']), str(row['blocks']),
                 first_pass_text(row['first_pass']), '예' if row['unresolved'] else '아니오',
                 *token_cells(row['tokens'])]
        lines.append('| ' + ' | '.join(cells) + ' |')
    total = [
        '합계', ', '.join(summary['tools']), duration(summary['seconds']), str(summary['blocks']),
        f'{summary["first_pass"]}/{summary["judged"]}', str(summary['unresolved']),
        *[number(summary['tokens'][name]) for name in transcripts.FIELDS],
    ]
    lines.append('| ' + ' | '.join(f'**{cell}**' for cell in total) + ' |')
    lines.append('')
    if summary['unknown_tokens']:
        lines.append(f'토큰이 미상인 task {summary["unknown_tokens"]}건은 합계에서 제외했다.')
    lines.append('벽시계 시간에는 세션을 쉬던 대기 시간이 섞여 있어 작업 시간이 아니다. '
                 '토큰은 task 종료 시점의 세션 기록에서 구간만 집계하므로, task 도중 세션이 바뀌면 일부가 빠질 수 있다.')
    return '\n'.join(lines) + '\n'


def summary_table(out: Path) -> str:
    entries = []
    for path in sorted(out.glob('*.md')):
        match = SUMMARY.search(path.read_text(encoding='utf-8'))
        if match:
            entries.append(json.loads(match.group(1)))
    entries.sort(key=lambda entry: (entry['started_at'], entry['slug']))
    lines = [
        '| 계획 | 도구 | task | 벽시계 시간 | 게이트 차단 | AC 첫 시도 통과 | 미해결 | 입력 | 캐시 읽기 | 캐시 쓰기 | 출력 |',
        '| --- | --- | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: |',
    ]
    for entry in entries:
        tokens = entry['tokens']
        suffix = f' (미상 {entry["unknown_tokens"]}건 제외)' if entry['unknown_tokens'] else ''
        cells = [f'[{entry["slug"]}]({entry["slug"]}.md)', ', '.join(entry['tools']), str(entry['tasks']),
                 duration(entry['seconds']), str(entry['blocks']), f'{entry["first_pass"]}/{entry["judged"]}',
                 str(entry['unresolved']), *[number(tokens[name]) for name in transcripts.FIELDS]]
        cells[7] += suffix
        lines.append('| ' + ' | '.join(cells) + ' |')
    return '\n'.join(lines)


def update_readme(out: Path) -> None:
    path = out / 'README.md'
    text = path.read_text(encoding='utf-8') if path.is_file() else ''
    block = f'{START}\n{summary_table(out)}\n{END}'
    if START in text and END in text:
        head, rest = text.split(START, 1)
        text = head + block + rest.split(END, 1)[1]
    else:
        text = text.rstrip('\n') + ('\n\n' if text else '') + block + '\n'
    path.write_text(text, encoding='utf-8')


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('plans', nargs='*', help='계획 slug 또는 파일 경로. 생략하면 이벤트의 모든 계획')
    parser.add_argument('--events', type=Path, default=REPO / '.metrics' / 'events.jsonl')
    parser.add_argument('--out', type=Path, default=REPO / 'docs' / 'workflow' / 'metrics')
    parser.add_argument('--claude-root', type=Path, default=Path.home() / '.claude' / 'projects')
    parser.add_argument('--codex-root', type=Path, default=Path.home() / '.codex' / 'sessions')
    args = parser.parse_args(argv)

    events = load_events(args.events)
    roots = {'claude': args.claude_root, 'codex': args.codex_root}
    by_slug = {}
    for event in events:
        by_slug.setdefault(slug_of(event['plan']), set()).add(event['plan'])
    wanted = [slug_of(plan) for plan in args.plans] or sorted(by_slug)
    args.out.mkdir(parents=True, exist_ok=True)
    for slug in wanted:
        rows = []
        for plan in sorted(by_slug.get(slug, ())):
            rows += build_rows(events, plan, roots)
        if not rows:
            print(f'{slug}: 종료된 task 이벤트가 없어 건너뛴다.', file=sys.stderr)
            continue
        rows.sort(key=lambda row: (row['started_at'], row['task_id']))
        (args.out / f'{slug}.md').write_text(render(slug, rows), encoding='utf-8')
    update_readme(args.out)
    return 0


if __name__ == '__main__':
    sys.exit(main())
