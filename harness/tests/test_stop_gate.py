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

    def test_other_session_is_not_gated(self):
        with PipelineRepo() as root:
            (root / 'src').mkdir()
            (root / 'src' / 'a').write_text('x')
            self.assertIsNone(stop_gate.evaluate(stop_hook(root, session='other'), root / 'rules.toml'))

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

    def test_unchanged_tree_after_pass_skips_checks(self):
        with PipelineRepo() as root:
            (root / 'src').mkdir()
            (root / 'src' / 'a').write_text('x')
            (root / '.ok').write_text('')
            self.assertIsNone(stop_gate.evaluate(stop_hook(root), root / 'rules.toml'))
            (root / 'rules.toml').write_text('[[rules]]\nglobs = ["*"]\ncommands = ["false"]\n')
            subprocess.run(['git', '-C', str(root), 'update-index', '--assume-unchanged', 'rules.toml'], check=True)
            self.assertIsNone(stop_gate.evaluate(stop_hook(root), root / 'rules.toml'))

    def test_allows_and_marks_unresolved_after_max_blocks(self):
        with PipelineRepo() as root:
            (root / 'src').mkdir()
            for n in range(1, state.MAX_GATE_BLOCKS + 1):
                (root / 'src' / 'a').write_text(str(n))
                self.assertIn(f'{n}/{state.MAX_GATE_BLOCKS}', stop_gate.evaluate(stop_hook(root), root / 'rules.toml'))
            (root / 'src' / 'a').write_text('last')
            self.assertIsNone(stop_gate.evaluate(stop_hook(root), root / 'rules.toml'))
            self.assertTrue(state.load(root)['task']['gate_unresolved'])

    def test_task_ac_replaces_path_rules_and_resets_block_count(self):
        with PipelineRepo() as root:
            (root / 'src').mkdir()
            (root / 'src' / 'a').write_text('x')
            self.assertIsNotNone(stop_gate.evaluate(stop_hook(root), root / 'rules.toml'))
            pipeline.main(['task', 'T1', '--ac', 'echo ac-failed; false'])
            reason = stop_gate.evaluate(stop_hook(root, tool='codex'), root / 'rules.toml')
            self.assertIn('task AC', reason)
            self.assertIn('1/3', reason)
            self.assertIn('ac-failed', reason)

    def test_no_changes_never_blocks(self):
        with PipelineRepo() as root:
            self.assertIsNone(stop_gate.evaluate(stop_hook(root), root / 'rules.toml'))


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



if __name__ == '__main__':
    unittest.main()
