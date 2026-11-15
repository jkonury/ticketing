#!/usr/bin/env python3
"""러너 산출물을 Prometheus gauge로 발행하고 읽기 전용 /metrics로 제공한다.

publish <run-dir> <phase> [--round N] [--artifacts DIR]
serve <runs-root> [--port 19100]: 최근 200개 독립 실행의 dashboard.prom만 노출한다.
"""
import argparse
import csv
import json
import math
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

PREFIX = 'ticketing_loadtest_'


def escape(value):
    return str(value).replace('\\', '\\\\').replace('\n', '\\n').replace('"', '\\"')


def publish(directory, phase, round_number=0, artifacts=None):
    directory = Path(directory)
    artifacts = Path(artifacts) if artifacts else directory
    metadata = dict(line.split(': ', 1) for line in (directory / 'env.txt').read_text().splitlines() if ': ' in line)
    labels = {'run_id': f'{directory.parent.name}/{directory.name}', 'scenario': metadata['scenario'],
              'scouter': 'on' if metadata['scouter'] == '활성' else 'off', 'phase': phase}
    lines = []
    def metric(metric_suffix, sample_value, **extra):
        value = sample_value
        if isinstance(value, bool): value = int(value)
        if not isinstance(value, (int, float)) or not math.isfinite(value): return
        tags = ','.join(f'{key}="{escape(item)}"' for key, item in {**labels, **extra}.items())
        lines.append(f'{PREFIX}{metric_suffix}{{{tags}}} {value}')
    def read(name):
        path = artifacts / name
        return json.loads(path.read_text()) if path.exists() else None

    metric('info', 1, tool=metadata['tool'], generator_cpus=metadata['k6-cpus'], app_cpus=metadata['app-cpus'])
    metric('updated_seconds', time.time())
    metric('warmup_round', round_number)
    metric('warmup_rounds_planned', int(metadata['warmup-rounds']))
    raw_limit = metadata.get('booking-p95-ms', '')
    metric('booking_p95_limit_seconds', int(raw_limit) / 1000 if raw_limit.isdigit() else (.3 if metadata.get('vus', '').isdigit() and int(metadata['vus']) <= 100 else 1.5))
    summary = read('gatling-summary.json')
    if summary:
        for outcome, values in summary.get('bookingOutcomes', {}).items():
            metric('booking_requests', values['count'], outcome=outcome)
            for percentile in ('p50', 'p95', 'p99'):
                value = values.get(percentile)
                if value is not None: metric('booking_latency_seconds', value / 1000, outcome=outcome, percentile=percentile)
        for flow, values in summary.get('metrics', {}).items():
            metric('flow_count', values.get('count'), flow=flow)
        metric('http_requests', summary.get('totalRequests'))
        metric('http_failed_requests', summary.get('failedRequests'))
        for name, values in summary.get('requests', {}).items():
            if name.startswith('sse_') and values.get('count', 0):
                metric('sse_success_ratio', 1 - values['failed'] / values['count'], stream=name)
    ready = read('readiness.json')
    if ready:
        for name in ('vus', 'registered', 'admitted', 'readyOnTime', 'readyLate', 'bookingAttempt', 'notReadyOnTime'):
            metric('readiness', ready.get(name), item=name)
    verdict = read('verdict.json')
    metric('verdict_available', verdict is not None)
    if verdict:
        metric('passed', verdict['passed'])
        for name, value in verdict['checks'].items():
            if isinstance(value, bool): metric('check_passed', value, check=name)
    integrity = read('integrity.json')
    if integrity:
        metric('integrity_ok', integrity['ok'])
        for name, value in integrity.get('checks', {}).items(): metric('integrity', value, check=name)
        for status, value in integrity.get('info', {}).get('bookingsByStatus', {}).items(): metric('bookings', value, status=status)
    delta = read('server-delta.json')
    if delta:
        metric('server_delta_valid', delta['valid'])
        for index, check in enumerate(delta['controlledInputChecks']):
            target = 'gateway' if index == 0 else 'core'
            metric('controlled_input_ok', check.get('ok'), target=target)
            metric('controlled_input_expected', check.get('expectedFromK6'), target=target)
            metric('controlled_input_observed', check.get('observedServerDelta'), target=target)
        for app, histograms in delta['histograms'].items():
            for histogram in histograms:
                dimensions = {key: str(histogram.get(key, '')) for key in ('uri', 'method', 'routeId', 'httpMethod', 'stage', 'route', 'pool', 'name')}
                dimensions.update(application=app, metric=histogram['metric'])
                metric('server_requests', histogram.get('count'), **dimensions)
                for quantile in ('p50', 'p95', 'p99'):
                    metric('server_latency_seconds', histogram.get(f'{quantile}Seconds'), percentile=quantile, **dimensions)
        for app, values in delta.get('gcDelta', {}).items(): metric('gc_pause_seconds', values.get('pauseSeconds'), application=app)
        for app, values in delta.get('hikariDelta', {}).items(): metric('hikari_timeouts', values.get('timeouts'), application=app)
    resources = artifacts / 'resources.csv'
    if resources.exists():
        with resources.open() as source:
            rows = list(csv.DictReader(source))
        for column, name in [('k6_rss_bytes', 'generator_rss_max_bytes'), ('k6_cpu_cores', 'generator_cpu_max_cores'), ('host_cpu_busy_fraction', 'host_cpu_max_ratio')]:
            values = [float(row[column]) for row in rows if row.get(column)]
            if values: metric(name, max(values))
        memory = [1 - float(row['host_memory_available_bytes']) / float(row['host_memory_total_bytes']) for row in rows if float(row['host_memory_total_bytes']) > 0]
        if memory: metric('host_memory_max_ratio', max(memory))
    # scrape가 부분 기록을 읽지 않도록 지표 파일을 원자적으로 교체한다.
    temporary = directory / 'dashboard.prom.tmp'
    temporary.write_text('\n'.join(lines) + '\n')
    temporary.replace(directory / 'dashboard.prom')


def serve(root, port):
    root = Path(root)
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path not in ('/metrics', '/health'):
                self.send_error(404)
                return
            if self.path == '/health':
                body = b'ok\n'
            else:
                files = sorted(root.glob('*/*/dashboard.prom'), key=lambda path: path.stat().st_mtime, reverse=True)[:200]
                content = ''.join(path.read_text() for path in files)
                families = sorted({line.split('{', 1)[0] for line in content.splitlines() if line})
                body = (''.join(f'# TYPE {name} gauge\n' for name in families) + content).encode()
            self.send_response(200)
            self.send_header('Content-Type', 'text/plain; version=0.0.4; charset=utf-8')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *_args):
            pass
    ThreadingHTTPServer(('0.0.0.0', port), Handler).serve_forever()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['publish', 'serve'])
    parser.add_argument('directory')
    parser.add_argument('phase', nargs='?', default='starting')
    parser.add_argument('--round', type=int, default=0)
    parser.add_argument('--artifacts')
    parser.add_argument('--port', type=int, default=19100)
    args = parser.parse_args()
    if args.action == 'serve': serve(args.directory, args.port)
    else: publish(args.directory, args.phase, args.round, args.artifacts)


if __name__ == '__main__':
    main()
