import json
import tempfile
import unittest
from pathlib import Path

import support

report = support.load_script('bin/report.py')

SID = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee'
PLAN = 'docs/workflow/plans/2026-11-27-demo-plan.md'


def event(kind, task, started, at, blocks=0, **extra):
    return {'event': kind, 'timestamp': at, 'run_id': 'r1', 'plan': PLAN, 'task_id': task,
            'task_started_at': started, 'task_elapsed_seconds': 0, 'tool': 'codex',
            'session_id': SID, 'gate_blocks': blocks, **extra}


def codex_row(at, inp, cached, out):
    usage = {'input_tokens': inp, 'cached_input_tokens': cached, 'cache_write_input_tokens': 0,
             'output_tokens': out, 'total_tokens': inp + out}
    return {'timestamp': at, 'type': 'event_msg',
            'payload': {'type': 'token_count', 'info': {'total_token_usage': usage}}}


class ReportTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.dir = Path(self._tmp.name)
        self.out = self.dir / 'out'
        self.out.mkdir()
        (self.out / 'README.md').write_text('# 지표\n\n손으로 쓴 설명\n\n<!-- report:start -->\n낡은 표\n<!-- report:end -->\n\n끝 설명\n')
        sessions = self.dir / 'codex' / '2026' / '11' / '27'
        sessions.mkdir(parents=True)
        (sessions / f'rollout-2026-11-27T17-00-00-{SID}.jsonl').write_text(''.join(
            json.dumps(row) + '\n' for row in [
                codex_row('2026-11-27T08:05:00.000Z', 1000, 400, 50),
                codex_row('2026-11-27T08:20:00.000Z', 3000, 1400, 150),
            ]))
        events = [
            event('gate', 'T1', '2026-11-27T08:00:00+00:00', '2026-11-27T08:06:00+00:00', 0, outcome='passed'),
            event('task_ended', 'T1', '2026-11-27T08:00:00+00:00', '2026-11-27T08:10:00+00:00', 0, reason='task_changed'),
            event('gate', 'T2', '2026-11-27T08:10:00+00:00', '2026-11-27T08:12:00+00:00', 1, outcome='blocked'),
            event('gate', 'T2', '2026-11-27T08:10:00+00:00', '2026-11-27T08:15:00+00:00', 1, outcome='passed'),
            event('task_ended', 'T2', '2026-11-27T08:10:00+00:00', '2026-11-27T08:20:00+00:00', 1, reason='task_changed'),
            # 재시작으로 같은 task가 다시 시작된 구간. 대화 기록이 없는 시간대라 미상이다.
            event('task_ended', 'T2', '2026-11-27T10:00:00+00:00', '2026-11-27T10:05:00+00:00', 0, reason='pipeline_ended'),
        ]
        self.events = self.dir / 'events.jsonl'
        self.events.write_text(''.join(json.dumps(row) + '\n' for row in events))

    def run_report(self, *plans):
        return report.main([*plans, '--events', str(self.events), '--out', str(self.out),
                            '--codex-root', str(self.dir / 'codex'), '--claude-root', str(self.dir / 'claude')])

    def test_task_table_and_totals(self):
        self.assertEqual(self.run_report('demo-plan'), 0)
        text = (self.out / 'demo-plan.md').read_text()
        self.assertIn('| T1 | codex | 10분 00초 | 0 | 예 | 아니오 | 600 | 400 | 0 | 50 |', text)
        self.assertIn('| T2 | codex | 10분 00초 | 1 | 아니오 | 아니오 | 1,000 | 1,000 | 0 | 100 |', text)
        self.assertIn('| T2 | codex | 5분 00초 | 0 | - | 아니오 | 미상 | 미상 | 미상 | 미상 |', text)
        self.assertIn('| **합계** | **codex** | **25분 00초** | **1** | **1/2** | **0** | **1,600** | **1,400** | **0** | **150** |', text)
        self.assertIn('토큰이 미상인 task 1건은 합계에서 제외했다.', text)

    def test_readme_only_marked_block_changes(self):
        self.run_report()
        readme = (self.out / 'README.md').read_text()
        self.assertTrue(readme.startswith('# 지표\n\n손으로 쓴 설명\n\n<!-- report:start -->\n'))
        self.assertTrue(readme.endswith('<!-- report:end -->\n\n끝 설명\n'))
        self.assertNotIn('낡은 표', readme)
        self.assertIn('[demo-plan](demo-plan.md)', readme)

    def test_rerun_is_identical_and_has_no_session_id(self):
        self.run_report()
        first = {path.name: path.read_text() for path in self.out.glob('*.md')}
        self.run_report()
        self.assertEqual(first, {path.name: path.read_text() for path in self.out.glob('*.md')})
        for text in first.values():
            self.assertNotIn(SID, text)


if __name__ == '__main__':
    unittest.main()
