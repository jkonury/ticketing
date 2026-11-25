import subprocess
import unittest

import support

from lib import gate

CONFIG = {
    'modules': {'core': ':core', 'loadtest/gatling': ':gatling'},
    'rules': [
        {'name': 'md', 'globs': ['*.md'], 'commands': ['docs {files}']},
        {'name': 'static', 'per_module': True, 'globs': ['loadtest/gatling/*'], 'commands': ['lint {module}']},
        {'name': 'module', 'per_module': True, 'globs': ['*'], 'commands': ['lint {module}', 'test {module}']},
        {'name': 'ignored', 'globs': ['tmp/*'], 'skip': True, 'commands': ['never']},
        {'name': 'web', 'globs': ['web/*'], 'commands': ['web-check']},
    ],
}


class SelectCommandsTest(unittest.TestCase):
    def test_first_matching_rule_wins_and_commands_dedupe(self):
        files = ['core/a.kt', 'core/b.kt', 'core/README.md', 'web/x.ts', 'web/y.ts']
        self.assertEqual(
            gate.select_commands(files, CONFIG),
            ['docs core/README.md', 'lint :core', 'test :core', 'web-check'],
        )

    def test_longest_module_prefix_and_static_only_rule(self):
        self.assertEqual(gate.select_commands(['loadtest/gatling/S.kt'], CONFIG), ['lint :gatling'])

    def test_skip_rule_and_unmatched_files_need_no_check(self):
        self.assertEqual(gate.select_commands(['tmp/a', 'scripts/x.sh'], CONFIG), [])

    def test_per_module_rule_does_not_match_outside_modules(self):
        self.assertEqual(gate.select_commands(['corefoo/a.kt'], CONFIG), [])

    def test_files_are_shell_quoted(self):
        self.assertEqual(gate.select_commands(['a b.md'], CONFIG), ["docs 'a b.md'"])


class WorkingTreeTest(unittest.TestCase):
    def test_changed_files_include_untracked_modified_and_both_rename_paths(self):
        with support.TempRepo() as root:
            (root / 'old.txt').write_text('1\n')
            subprocess.run(['git', '-C', str(root), 'add', '.'], check=True)
            subprocess.run(['git', '-C', str(root), 'commit', '-qm', 'old'], check=True)
            subprocess.run(['git', '-C', str(root), 'mv', 'old.txt', 'new.txt'], check=True)
            (root / 'README.md').write_text('changed\n')
            (root / 'dir').mkdir()
            (root / 'dir' / 'n.kt').write_text('x\n')
            self.assertEqual(gate.changed_files(root), ['README.md', 'dir/n.kt', 'new.txt', 'old.txt'])

    def test_fingerprint_tracks_untracked_content(self):
        with support.TempRepo() as root:
            clean = gate.fingerprint(root)
            (root / 'n.txt').write_text('a\n')
            first = gate.fingerprint(root)
            self.assertNotEqual(clean, first)
            self.assertEqual(first, gate.fingerprint(root))
            (root / 'n.txt').write_text('b\n')
            self.assertNotEqual(first, gate.fingerprint(root))

    def test_run_returns_only_failures_with_output_tail(self):
        with support.TempRepo() as root:
            failures = gate.run(root, ['true', 'echo boom; exit 3'])
            self.assertEqual([(f.command, f.returncode, f.output) for f in failures],
                             [('echo boom; exit 3', 3, 'boom')])


if __name__ == '__main__':
    unittest.main()
