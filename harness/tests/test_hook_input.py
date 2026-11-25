import json
import unittest

import support  # noqa: F401

from lib import hook_input


class HookInputTest(unittest.TestCase):
    def test_parses_common_stop_fields_for_both_tools(self):
        payload = {
            'hook_event_name': 'Stop', 'session_id': 's1', 'cwd': '/tmp',
            'stop_hook_active': True, 'transcript_path': '/t.jsonl',
        }
        for tool in hook_input.TOOLS:
            hook = hook_input.parse(tool, json.dumps(payload))
            self.assertEqual((hook.tool, hook.event, hook.session_id), (tool, 'Stop', 's1'))
            self.assertTrue(hook.stop_hook_active)
            self.assertEqual(hook.transcript_path, '/t.jsonl')

    def test_joins_codex_argv_command(self):
        payload = {'tool_name': 'Bash', 'tool_input': {'command': ['python3', 'harness/bin/pipeline.py', 'start']}}
        hook = hook_input.parse('codex', json.dumps(payload))
        self.assertEqual(hook.command, 'python3 harness/bin/pipeline.py start')

    def test_rejects_unknown_tool(self):
        with self.assertRaises(ValueError):
            hook_input.parse('gemini', '{}')

    def test_stop_block_shape(self):
        self.assertEqual(json.loads(hook_input.stop_block('고쳐라')), {'decision': 'block', 'reason': '고쳐라'})


if __name__ == '__main__':
    unittest.main()
