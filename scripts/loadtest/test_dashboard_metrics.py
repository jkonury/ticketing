"""스크립트 결과 exporter의 누락·라벨·예열 격리·단위 변환을 확인한다."""
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location('dashboard_metrics', Path(__file__).with_name('dashboard-metrics.py'))
METRICS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(METRICS)


class DashboardMetricsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name) / 'run' / '1'
        self.directory.mkdir(parents=True)
        (self.directory / 'env.txt').write_text('scenario: hot-seats\ntool: gatling\nscouter: 활성\nk6-cpus: 0-3\napp-cpus: 4-15\nwarmup-rounds: 15\nbooking-p95-ms: 300\nsecret: NEVER_EXPOSE_THIS\n')

    def write(self, name, value, directory=None):
        ((directory or self.directory) / name).write_text(json.dumps(value))

    def publish(self, phase='measurement', **options):
        METRICS.publish(self.directory, phase, **options)
        return (self.directory / 'dashboard.prom').read_text()

    def test_누락된_결과는_0이나_합격으로_채우지_않는다(self):
        result = self.publish()
        self.assertNotIn('ticketing_loadtest_passed{', result)
        self.assertNotIn('booking_latency_seconds', result)
        self.assertIn('verdict_available', result)
        self.assertNotIn('NEVER_EXPOSE_THIS', result)

    def test_예열_표본과_본_측정은_섞이지_않는다(self):
        warmup = self.directory / 'warmup-1'
        warmup.mkdir()
        self.write('gatling-summary.json', {'bookingOutcomes': {'success': {'count': 5, 'p95': 250}}, 'metrics': {}}, warmup)
        warm_result = self.publish('warmup', round_number=1, artifacts=warmup)
        self.assertIn('percentile="p95"} 0.25', warm_result)
        self.assertIn('phase="warmup"', warm_result)
        self.assertNotIn('booking_latency_seconds', self.publish())

    def test_서버_차분과_정합성_실패를_그대로_발행한다(self):
        self.write('server-delta.json', {'valid': False, 'controlledInputChecks': [{'ok': False, 'expectedFromK6': 1000, 'observedServerDelta': 999}], 'histograms': {'gateway': [{'metric': 'ticketing_gateway_stage_seconds', 'stage': 'body', 'count': 1000, 'p95Seconds': .02} ]}})
        self.write('verdict.json', {'passed': False, 'checks': {'serverDelta': False}})
        result = self.publish('failed')
        self.assertIn('metric="ticketing_gateway_stage_seconds"', result)
        self.assertRegex(result, r'server_latency_seconds\{[^\n]*percentile="p95"[^\n]*\} 0\.02')
        self.assertIn('ticketing_loadtest_passed{', result)
        self.assertIn('phase="failed"} 0', result)
        self.assertIn('target="gateway"} 999', result)

    def test_라벨의_따옴표와_역슬래시를_이스케이프한다(self):
        self.assertEqual(METRICS.escape('a"b\\c\nd'), 'a\\"b\\\\c\\nd')


if __name__ == '__main__':
    unittest.main()
