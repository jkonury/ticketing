"""집계·판정 회귀: python3 -m unittest discover -s scripts/loadtest -p 'test_*.py'."""
import copy
import importlib.util
import json
import struct
import subprocess
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location('gatling_results', Path(__file__).with_name('gatling-results.py'))
RESULTS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RESULTS)
ROOT = Path(__file__).resolve().parents[2]


class GatlingResultsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.summary = {
            'metrics': {key: {'count': 10} for key in ('flow_booking_created', 'flow_payment_requested', 'booking_confirmed')},
            'bookingOutcomes': {name: {'count': count, 'p50': 20, 'p95': 40 if count else None, 'p99': 80}
                                for name, count in [('all', 1000), ('success', 10), ('conflict', 990), ('other', 0), ('rateLimited', 0)]},
            'requests': {'sse_queue': {'count': 1000, 'failed': 0}}, 'totalRequests': 3000, 'failedRequests': 0,
        }
        self.ready = {key: 1000 for key in ('vus', 'registered', 'admitted', 'readyOnTime', 'bookingAttempt')}
        self.ready.update(readyLate=0, fullContention=True)
        self.integrity = {'ok': True, 'info': {'bookingsByStatus': {'CONFIRMED': 10}}}
        self.delta = {'valid': True, 'controlledInputChecks': [{'ok': True}, {'ok': True}]}
        self.write('readiness.json', self.ready)
        self.write('integrity.json', self.integrity)
        self.write('server-delta.json', self.delta)
        self.write('server-delta-warmup.json', self.delta)
        self.write('isolation.json', {'isolated': True})

    def write(self, name, data):
        (self.directory / name).write_text(json.dumps(data))

    def judge(self, summary=None, warmup=False, code=0):
        self.write('gatling-summary.json', summary or self.summary)
        return RESULTS.judge(self.directory, 'hot-seats', 300, code, warmup, True, True)['passed']

    def test_정상_입력은_합격한다(self):
        self.assertTrue(self.judge())

    def test_미달_누락을_합격시키지_않는다(self):
        for mutation in ('successSlow', 'allSlow', 'noSuccess', '429', 'missingSamples', 'unexpected', 'notConfirmed'):
            with self.subTest(mutation=mutation):
                summary = copy.deepcopy(self.summary)
                outcomes = summary['bookingOutcomes']
                if mutation == 'successSlow': outcomes['success']['p95'] = 300
                if mutation == 'allSlow': outcomes['all']['p95'] = 300
                if mutation == 'noSuccess': outcomes['success']['count'] = 0
                if mutation == '429': outcomes['rateLimited']['count'] = 1
                if mutation == 'missingSamples': outcomes['conflict']['count'] -= 1
                if mutation == 'unexpected': outcomes['other']['count'] = 10
                if mutation == 'notConfirmed': summary['metrics']['booking_confirmed']['count'] = 9
                self.assertFalse(self.judge(summary))
        self.assertFalse(self.judge(code=2))
        self.assertFalse(self.judge(code=1))

    def test_예열은_지연_미달만_허용한다(self):
        self.summary['bookingOutcomes']['all']['p95'] = 2000
        self.summary['bookingOutcomes']['success']['p95'] = 2000
        self.assertTrue(self.judge(warmup=True, code=2))
        self.summary['requests']['sse_queue']['failed'] = 1
        self.assertFalse(self.judge(warmup=True, code=2))

    def test_준비_차분_격리_정합성_누락을_거부한다(self):
        mutations = [('readiness.json', {**self.ready, 'registered': 999}),
                     ('server-delta.json', {**self.delta, 'valid': False}),
                     ('server-delta.json', {**self.delta, 'controlledInputChecks': [{'ok': None}]}),
                     ('isolation.json', {'isolated': False}), ('integrity.json', {'ok': False})]
        for filename, value in mutations:
            with self.subTest(filename=filename, value=value):
                old = (self.directory / filename).read_text()
                self.write(filename, value)
                self.assertFalse(self.judge())
                (self.directory / filename).write_text(old)
        (self.directory / 'server-delta.json').unlink()
        self.assertFalse(self.judge())

    def test_알려진_바이너리_표본의_지연과_실패를_읽는다(self):
        integer = lambda value: struct.pack('>i', value)
        string = lambda value: integer(len(value)) + value.encode('latin1') + (b'\0' if value else b'')
        data = b'\0' + string('3.16.0') + string('test') + struct.pack('>q', 0) + string('') + integer(0) + integer(0)
        for index, duration in enumerate(range(1, 101)):
            data += b'\1' + integer(0)
            data += integer(1) + string('booking_create') if index == 0 else integer(-1)
            data += integer(1000) + integer(1000 + duration) + (b'\0' if index == 99 else b'\1')
            data += integer(2) + string('') if index == 0 else integer(-2)
        path = self.directory / 'simulation.log'
        path.write_bytes(data)
        rows = list(RESULTS.LogReader(path).requests())
        self.assertEqual(RESULTS.distribution([row[1] for row in rows]), {'count': 100, 'p50': 50, 'p95': 95, 'p99': 99})
        self.assertEqual(sum(not row[2] for row in rows), 1)
        path.write_bytes(data[:-1])
        with self.assertRaises(ValueError): list(RESULTS.LogReader(path).requests())

    def test_TERM은_자식을_정리하고_다음_반복_없이_종료한다(self):
        runner = (ROOT / 'scripts/loadtest/run.sh').read_text()
        handlers = runner[runner.index('PIDS=()'):runner.index('build_tasks=')]
        script = self.directory / 'signal.sh'
        script.write_text('set -euo pipefail\n' + handlers + '\nsleep 30 &\nLOAD_PID=$!\necho ready\nwait "$LOAD_PID" || true\necho unexpected-next-run\n')
        process = subprocess.Popen(['bash', str(script)], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            self.assertEqual(process.stdout.readline().strip(), 'ready')
            process.terminate()
            output, error = process.communicate(timeout=5)
            self.assertEqual(process.returncode, 143, error)
            self.assertNotIn('unexpected-next-run', output)
        finally:
            if process.poll() is None:
                process.kill()
                process.communicate()

    def test_미지원_옵션과_잘못된_한도는_기동_전에_거부한다(self):
        for options in [('k6', '300'), ('gatling', '0'), ('gatling', '-1'), ('gatling', '1.5'), ('gatling', '999999999999')]:
            result = subprocess.run(['bash', 'scripts/loadtest/run.sh', '--scenario', 'hot-seats', '--tool', options[0], '--booking-p95-ms', options[1]], cwd=ROOT, capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('--booking-p95-ms', result.stderr)


if __name__ == '__main__':
    unittest.main()
