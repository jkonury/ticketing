import json
import tempfile
import unittest
from pathlib import Path

import support  # noqa: F401
from lib import transcripts

SID = '11111111-2222-3333-4444-555555555555'


def write(path: Path, rows: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(''.join(json.dumps(row) + '\n' for row in rows))


def claude_row(at, msg_id, inp, read, create, out):
    return {'type': 'assistant', 'timestamp': at, 'message': {'id': msg_id, 'usage': {
        'input_tokens': inp, 'cache_read_input_tokens': read,
        'cache_creation_input_tokens': create, 'output_tokens': out}}}


def codex_row(at, inp, cached, out):
    usage = {'input_tokens': inp, 'cached_input_tokens': cached, 'cache_write_input_tokens': 0,
             'output_tokens': out, 'total_tokens': inp + out}
    return {'timestamp': at, 'type': 'event_msg', 'payload': {
        'type': 'token_count', 'info': {'total_token_usage': usage}}}


class ClaudeTokens(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.root = Path(self._tmp.name)

    def test_dedupes_messages_and_respects_range(self):
        write(self.root / 'proj' / f'{SID}.jsonl', [
            claude_row('2026-11-27T08:00:00.000Z', 'a', 1, 10, 100, 5),
            claude_row('2026-11-27T08:05:00.000Z', 'b', 2, 20, 0, 6),
            claude_row('2026-11-27T08:05:01.000Z', 'b', 2, 20, 0, 9),
            claude_row('2026-11-27T08:10:00.000Z', 'c', 4, 40, 0, 7),
            claude_row('2026-11-27T09:00:00.000Z', 'd', 8, 80, 0, 8),
        ])
        result = transcripts.task_tokens(
            'claude', SID, '2026-11-27T08:04:00+00:00', '2026-11-27T08:30:00+00:00', self.root)
        self.assertEqual(result, {'input': 6, 'cache_read': 60, 'cache_write': 0, 'output': 16})

    def test_unknown_when_missing_or_empty_range(self):
        self.assertIsNone(transcripts.task_tokens(
            'claude', SID, '2026-11-27T08:00:00+00:00', '2026-11-27T09:00:00+00:00', self.root))
        write(self.root / 'proj' / f'{SID}.jsonl', [claude_row('2026-11-27T07:00:00Z', 'a', 1, 1, 1, 1)])
        self.assertIsNone(transcripts.task_tokens(
            'claude', SID, '2026-11-27T08:00:00+00:00', '2026-11-27T09:00:00+00:00', self.root))


class CodexTokens(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.root = Path(self._tmp.name)
        self.path = self.root / '2026' / '11' / '27' / f'rollout-2026-11-27T17-00-00-{SID}.jsonl'

    def test_cumulative_difference_and_cache_normalization(self):
        write(self.path, [
            codex_row('2026-11-27T08:00:00.000Z', 1000, 400, 50),
            codex_row('2026-11-27T08:05:00.000Z', 3000, 1400, 150),
            codex_row('2026-11-27T08:05:00.000Z', 3000, 1400, 150),
            codex_row('2026-11-27T08:10:00.000Z', 5000, 2400, 250),
            codex_row('2026-11-27T09:00:00.000Z', 9000, 5000, 400),
        ])
        result = transcripts.task_tokens(
            'codex', SID, '2026-11-27T08:04:00+00:00', '2026-11-27T08:30:00+00:00', self.root)
        self.assertEqual(result, {'input': 2000, 'cache_read': 2000, 'cache_write': 0, 'output': 200})

    def test_first_task_uses_zero_baseline(self):
        write(self.path, [codex_row('2026-11-27T08:05:00.000Z', 100, 40, 10)])
        result = transcripts.task_tokens(
            'codex', SID, '2026-11-27T08:00:00+00:00', '2026-11-27T08:30:00+00:00', self.root)
        self.assertEqual(result, {'input': 60, 'cache_read': 40, 'cache_write': 0, 'output': 10})

    def test_unknown_when_missing_or_no_event_in_range(self):
        self.assertIsNone(transcripts.task_tokens(
            'codex', SID, '2026-11-27T08:00:00+00:00', '2026-11-27T09:00:00+00:00', self.root))
        write(self.path, [codex_row('2026-11-27T07:00:00.000Z', 100, 40, 10)])
        self.assertIsNone(transcripts.task_tokens(
            'codex', SID, '2026-11-27T08:00:00+00:00', '2026-11-27T09:00:00+00:00', self.root))

    def test_unknown_tool_or_session(self):
        self.assertIsNone(transcripts.task_tokens('x', SID, '2026-11-27T08:00:00+00:00', '2026-11-27T09:00:00+00:00', self.root))
        self.assertIsNone(transcripts.task_tokens('codex', None, '2026-11-27T08:00:00+00:00', '2026-11-27T09:00:00+00:00', self.root))


if __name__ == '__main__':
    unittest.main()
