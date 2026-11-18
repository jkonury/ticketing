"""Kafka 시계열 교체와 실제 누락을 차분 검사에서 구분한다."""
import contextlib
import importlib.util
import io
import json
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location('metrics_delta', Path(__file__).with_name('metrics-delta.py'))
METRICS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(METRICS)


class MetricsDeltaTest(unittest.TestCase):
    def test_토픽별_시계열_교체만_허용하고_실제_누락은_오류로_남긴다(self):
        for metric in ('kafka_consumer_fetch_manager_bytes_consumed_total',
                       'kafka_consumer_fetch_manager_records_consumed_total'):
            key = f'{metric}{{client_id="consumer-1"}}'
            matching = f'{metric}{{client_id="consumer-1",topic="payments"}}'
            different = f'{metric}{{client_id="consumer-2",topic="payments"}}'
            cases = (
                ('정상 교체', key, 0, {matching: 10}, 0),
                ('비영 누락', key, 1, {matching: 10}, 2),
                ('다른 라벨', key, 0, {different: 10}, 2),
                ('토픽별 시계열 없음', key, 0, {}, 2),
                ('토픽별 시계열 누락', matching, 0, {}, 2),
                ('비 Kafka 누락', 'ticketing_example_total{client_id="consumer-1"}', 0, {matching: 10}, 2),
            )
            for name, before_key, value, post_series, expected in cases:
                with self.subTest(metric=metric, case=name), tempfile.TemporaryDirectory() as directory:
                    path = Path(directory)
                    for filename, series in (('pre.json', {before_key: value}), ('post.json', post_series)):
                        (path / filename).write_text(json.dumps({
                            'collectedAtEpoch': 1,
                            'apps': {'core': {'ok': True, 'series': series}},
                        }))
                    with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                        result = METRICS.diff(path / 'pre.json', path / 'post.json', path / 'out.json',
                                              'hot-seats', None, 'test')
                    output = json.loads((path / 'out.json').read_text())
                    self.assertEqual(expected, result)
                    self.assertEqual(expected == 0, output['valid'])
                    if expected:
                        self.assertEqual('series_missing', output['errors'][0]['type'])
                        self.assertEqual(before_key, output['errors'][0]['series'])


if __name__ == '__main__':
    unittest.main()
