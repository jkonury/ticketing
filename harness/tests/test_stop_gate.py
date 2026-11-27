import json
import os
import subprocess
import sys
import unittest
import unittest.mock

import support

from lib import hook_input, state

stop_gate = support.load_script('hooks/stop_gate.py')
bind_session = support.load_script('hooks/bind_session.py')
pipeline = support.load_script('bin/pipeline.py')


def metric_events(root):
    path = state.state_path(root).with_name('events.jsonl')
    return [json.loads(line) for line in path.read_text().splitlines()] if path.is_file() else []


def stop_hook(root, session='s1', tool='claude'):
    payload = {'hook_event_name': 'Stop', 'session_id': session, 'cwd': str(root), 'stop_hook_active': False}
    return hook_input.parse(tool, json.dumps(payload))


def bash_hook(root, command, session='s1', tool='claude'):
    payload = {'hook_event_name': 'PostToolUse', 'session_id': session, 'cwd': str(root),
               'tool_name': 'Bash', 'tool_input': {'command': command}}
    return hook_input.parse(tool, json.dumps(payload))


class PipelineRepo(support.TempRepo):
    """파이프라인을 시작하고 세션 s1에 묶은 임시 저장소. 게이트 규칙은 `.ok` 파일이 있어야 통과한다."""

    def __enter__(self):
        root = super().__enter__()
        self.config = root / 'rules.toml'
        self.config.write_text(
            '[[rules]]\nname = "all"\nglobs = ["src/*"]\ncommands = ["test -e .ok"]\n'
        )
        subprocess.run(['git', '-C', str(root), 'add', '.'], check=True)
        subprocess.run(['git', '-C', str(root), 'commit', '-qm', 'rules'], check=True)
        self._cwd = _chdir(root)
        pipeline.main(['start', '--plan', 'p.md'])
        bind_session.bind(bash_hook(root, 'python3 harness/bin/pipeline.py start --plan p.md'))
        return root

    def __exit__(self, *exc):
        _chdir(self._cwd)
        super().__exit__(*exc)


def _chdir(path):
    old = os.getcwd()
    os.chdir(path)
    return old


class StopGateTest(unittest.TestCase):
    def setUp(self):
        self._stdout = sys.stdout
        sys.stdout = open('/dev/null', 'w')

    def tearDown(self):
        sys.stdout.close()
        sys.stdout = self._stdout

    def test_no_pipeline_state_never_blocks(self):
        with support.TempRepo() as root:
            (root / 'src').mkdir()
            (root / 'src' / 'a').write_text('x')
            self.assertIsNone(stop_gate.evaluate(stop_hook(root), root / 'missing.toml'))
            self.assertEqual(metric_events(root), [])

    def test_other_session_is_not_gated(self):
        with PipelineRepo() as root:
            (root / 'src').mkdir()
            (root / 'src' / 'a').write_text('x')
            self.assertIsNone(stop_gate.evaluate(stop_hook(root, session='other'), root / 'rules.toml'))
            self.assertEqual(metric_events(root), [])

    def test_bind_only_once_and_only_for_start_or_resume(self):
        with PipelineRepo() as root:
            self.assertEqual(state.load(root)['session_id'], 's1')
            self.assertFalse(bind_session.bind(bash_hook(root, 'harness/bin/pipeline.py start', session='s2')))
            pipeline.main(['resume'])
            self.assertFalse(bind_session.bind(bash_hook(root, 'ls', session='s2')))
            self.assertTrue(bind_session.bind(bash_hook(root, './harness/bin/pipeline.py resume', session='s2', tool='codex')))
            self.assertEqual((state.load(root)['session_id'], state.load(root)['tool']), ('s2', 'codex'))

    def test_blocks_on_failure_then_passes_after_fix(self):
        with PipelineRepo() as root:
            (root / 'src').mkdir()
            (root / 'src' / 'a').write_text('x')
            reason = stop_gate.evaluate(stop_hook(root), root / 'rules.toml')
            self.assertIn('1/3', reason)
            self.assertIn('$ test -e .ok', reason)
            (root / '.ok').write_text('')
            self.assertIsNone(stop_gate.evaluate(stop_hook(root), root / 'rules.toml'))
            task = state.load(root)['task']
            self.assertEqual(task['gate_blocks'], 1)
            self.assertIsNotNone(task['last_pass_fingerprint'])
            events = metric_events(root)
            self.assertEqual([row['outcome'] for row in events], ['blocked', 'passed'])
            for row in events:
                self.assertEqual(row['event'], 'gate')
                self.assertEqual(row['run_id'], state.load(root)['run_id'])
                self.assertEqual((row['plan'], row['task_id'], row['tool'], row['session_id']),
                                 ('p.md', '_', 'claude', 's1'))
                self.assertEqual((row['source'], row['gate_blocks']), ('path_rules', 1))
                self.assertGreaterEqual(row['duration_seconds'], 0)
                self.assertGreaterEqual(row['task_elapsed_seconds'], 0)

    def test_unchanged_tree_after_pass_skips_checks(self):
        with PipelineRepo() as root:
            (root / 'src').mkdir()
            (root / 'src' / 'a').write_text('x')
            (root / '.ok').write_text('')
            self.assertIsNone(stop_gate.evaluate(stop_hook(root), root / 'rules.toml'))
            (root / 'rules.toml').write_text('[[rules]]\nglobs = ["*"]\ncommands = ["false"]\n')
            subprocess.run(['git', '-C', str(root), 'update-index', '--assume-unchanged', 'rules.toml'], check=True)
            self.assertIsNone(stop_gate.evaluate(stop_hook(root), root / 'rules.toml'))
            self.assertEqual([row['outcome'] for row in metric_events(root)], ['passed'])

    def test_allows_and_marks_unresolved_after_max_blocks(self):
        with PipelineRepo() as root:
            (root / 'src').mkdir()
            for n in range(1, state.MAX_GATE_BLOCKS + 1):
                (root / 'src' / 'a').write_text(str(n))
                self.assertIn(f'{n}/{state.MAX_GATE_BLOCKS}', stop_gate.evaluate(stop_hook(root), root / 'rules.toml'))
            (root / 'src' / 'a').write_text('last')
            self.assertIsNone(stop_gate.evaluate(stop_hook(root), root / 'rules.toml'))
            self.assertTrue(state.load(root)['task']['gate_unresolved'])
            self.assertIsNone(stop_gate.evaluate(stop_hook(root), root / 'rules.toml'))
            events = metric_events(root)
            self.assertEqual([row['outcome'] for row in events], ['blocked'] * state.MAX_GATE_BLOCKS + ['unresolved'])
            self.assertEqual([row['gate_blocks'] for row in events], [1, 2, 3, 3])
            self.assertNotIn('duration_seconds', events[-1])

    def test_task_ac_replaces_path_rules_and_resets_block_count(self):
        with PipelineRepo() as root:
            (root / 'src').mkdir()
            (root / 'src' / 'a').write_text('x')
            self.assertIsNotNone(stop_gate.evaluate(stop_hook(root), root / 'rules.toml'))
            pipeline.main(['task', 'T1', '--ac', 'echo ac-failed; false'])
            pipeline.main(['resume'])
            bind_session.bind(bash_hook(root, 'harness/bin/pipeline.py resume', session='s2', tool='codex'))
            reason = stop_gate.evaluate(stop_hook(root, session='s2', tool='codex'), root / 'rules.toml')
            self.assertIn('task AC', reason)
            self.assertIn('1/3', reason)
            self.assertIn('ac-failed', reason)
            row = metric_events(root)[-1]
            self.assertEqual((row['task_id'], row['source'], row['tool'], row['session_id']), ('T1', 'ac', 'codex', 's2'))
            self.assertNotIn('ac-failed', json.dumps(row))

    def test_no_changes_never_blocks(self):
        with PipelineRepo() as root:
            self.assertIsNone(stop_gate.evaluate(stop_hook(root), root / 'rules.toml'))
            self.assertEqual(metric_events(root), [])

    def test_changes_without_matching_checks_do_not_count_as_attempt(self):
        with PipelineRepo() as root:
            (root / 'README.md').write_text('문서만 변경')
            self.assertIsNone(stop_gate.evaluate(stop_hook(root), root / 'rules.toml'))
            self.assertEqual(metric_events(root), [])

    def test_metrics_write_error_preserves_block_decision(self):
        with PipelineRepo() as root:
            (root / 'src').mkdir()
            (root / 'src' / 'a').write_text('x')
            state.state_path(root).with_name('events.jsonl').mkdir()
            with unittest.mock.patch('sys.stderr') as stderr:
                self.assertIn('1/3', stop_gate.evaluate(stop_hook(root), root / 'rules.toml'))
            self.assertIn('metrics:', stderr.write.call_args_list[0].args[0])
            self.assertEqual(state.load(root)['task']['gate_blocks'], 1)


class PipelineCliTest(unittest.TestCase):
    def setUp(self):
        self._stdout = sys.stdout
        sys.stdout = open('/dev/null', 'w')

    def tearDown(self):
        sys.stdout.close()
        sys.stdout = self._stdout

    def test_start_refuses_second_pipeline_without_force_and_end_clears(self):
        with PipelineRepo() as root:
            with unittest.mock.patch('sys.stderr'):
                self.assertEqual(pipeline.main(['start', '--plan', 'q.md']), 1)
            self.assertEqual(pipeline.main(['start', '--plan', 'q.md', '--force']), 0)
            self.assertIsNone(state.load(root)['session_id'])
            self.assertEqual(pipeline.main(['end']), 0)
            self.assertIsNone(state.load(root))

    def test_task_exit_metrics_survive_transition_and_end(self):
        with PipelineRepo() as root:
            run_id = state.load(root)['run_id']
            with unittest.mock.patch.object(state, 'now', side_effect=[
                '2026-11-27T00:00:01+00:00', '2026-11-27T00:00:04+00:00',
                '2026-11-27T00:00:04+00:00', '2026-11-27T00:00:09+00:00',
            ]):
                pipeline.main(['task', 'T1'])
                pipeline.main(['task', 'T2'])
                pipeline.main(['end'])
            events = metric_events(root)
            self.assertEqual([row['event'] for row in events], ['task_ended', 'task_ended'])
            self.assertEqual([row['run_id'] for row in events], [run_id, run_id])
            self.assertEqual([row['task_id'] for row in events], ['T1', 'T2'])
            self.assertEqual([row['reason'] for row in events], ['task_changed', 'pipeline_ended'])
            self.assertEqual([row['task_elapsed_seconds'] for row in events], [3, 5])

    def test_forced_restart_closes_old_task_under_old_run(self):
        with PipelineRepo() as root:
            run_id = state.load(root)['run_id']
            pipeline.main(['task', 'T1'])
            pipeline.main(['start', '--plan', 'q.md', '--force'])
            self.assertNotEqual(state.load(root)['run_id'], run_id)
            row, = metric_events(root)
            self.assertEqual((row['run_id'], row['plan'], row['task_id'], row['reason']),
                             (run_id, 'p.md', 'T1', 'replaced'))

    def test_verify_shares_stop_attempts_and_only_accepts_current_tree(self):
        with PipelineRepo() as root:
            (root / 'src').mkdir()
            change = root / 'src' / 'a'
            change.write_text('x')
            pipeline.main(['task', 'T1', '--ac', 'test -f .ok'])
            verify = ['verify', '--config', 'rules.toml']
            self.assertIn('1/3', stop_gate.evaluate(stop_hook(root), root / 'rules.toml'))
            with unittest.mock.patch('sys.stderr'):
                self.assertEqual(pipeline.main(verify), 1)
            (root / '.ok').touch()
            self.assertEqual(pipeline.main(verify), 0)
            self.assertEqual(pipeline.main(verify), 0)
            (root / '.ok').unlink()
            with unittest.mock.patch('sys.stderr'):
                self.assertEqual(pipeline.main(verify), 1)
                self.assertEqual(pipeline.main(verify), 1)
            self.assertIsNone(stop_gate.evaluate(stop_hook(root), root / 'rules.toml'))
            self.assertEqual([row['outcome'] for row in metric_events(root)],
                             ['blocked', 'blocked', 'passed', 'blocked', 'unresolved'])

    def test_verify_rejects_unbound_missing_task_clean_or_unchecked_tree(self):
        with PipelineRepo() as root, unittest.mock.patch('sys.stderr'):
            verify = ['verify', '--config', 'rules.toml']
            self.assertEqual(pipeline.main(verify), 1)
            pipeline.main(['task', 'T1'])
            self.assertEqual(pipeline.main(verify), 1)
            (root / 'README.md').write_text('검사 경로 밖의 변경')
            self.assertEqual(pipeline.main(verify), 1)
            pipeline.main(['task', 'T2', '--ac', 'true'])
            pipeline.main(['resume'])
            self.assertEqual(pipeline.main(verify), 1)
            self.assertEqual([row['event'] for row in metric_events(root)], ['task_ended'])

    def test_verify_rejects_change_made_by_the_check_itself(self):
        with PipelineRepo() as root, unittest.mock.patch('sys.stderr'):
            (root / 'README.md').write_text('검사 전 변경')
            pipeline.main(['task', 'T1', '--ac', 'echo changed > README.md'])
            self.assertEqual(pipeline.main(['verify', '--config', 'rules.toml']), 1)


if __name__ == '__main__':
    unittest.main()
